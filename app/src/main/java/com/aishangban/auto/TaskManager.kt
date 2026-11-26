package com.aishangban.auto

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager

/**
 * 自动点击任务调度器。
 *
 * 闪退修复点 #5：
 * 1) activeInstance / running 使用 @Volatile，避免多线程可见性问题；
 * 2) 工作线程的 wakeAndUnlock / Thread.sleep 全部捕获异常与中断；
 * 3) WakeLock 获取与释放成对出现，异常路径也会释放；
 * 4) 所有回调前检查 running，任务停止后不再回调已销毁的界面；
 * 5) cleanup 幂等，重复 stop 不会二次 quit 线程或释放已释放的锁。
 */
class TaskManager private constructor() {

    interface TaskCallback {
        fun onStepStart(step: Int)
        fun onStepSuccess(step: Int)
        fun onTaskError(message: String)
        fun onTaskFinished() {}
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile
    private var running = false
    private var steps: List<Triple<Int, ClickPoint, Long>> = emptyList()
    private var callback: TaskCallback? = null
    private var logManager: LogManager? = null

    fun start(context: Context, steps: List<Triple<Int, ClickPoint, Long>>, callback: TaskCallback) {
        if (steps.size < MIN_STEPS) {
            callback.onTaskError("至少需要2个坐标点")
            return
        }

        this.steps = steps
        this.callback = callback
        this.logManager = LogManager(context)
        // 先置为运行态，等待服务重连期间也持有唤醒锁、允许重试
        this.running = true

        // v1.0.3：进程被回收重启后，系统无障碍开关仍在，但服务实例尚未绑定。
        // 原实现直接判定「服务未启动」并放弃任务；这里改为等待服务重连后再执行。
        if (!AutoAccessibilityService.isRunning) {
            if (AccessibilityUtils.isEnabledInSystem(context)) {
                logManager?.log("等待服务", "系统无障碍已开启，等待服务重新连接后执行")
                waitForServiceAndRun(context, 0)
            } else {
                logManager?.log("启动失败", "无障碍服务未开启")
                callback.onTaskError("无障碍服务未开启，请在系统设置中打开")
                cleanup()
            }
            return
        }

        launchWorker(context)
    }

    /** 等待无障碍服务重新绑定，最多重试若干次，期间保持 WakeLock 防止进程被回收。 */
    private fun waitForServiceAndRun(context: Context, attempt: Int) {
        if (!running) return
        if (AutoAccessibilityService.isRunning) {
            logManager?.log("服务已重连", "开始执行任务")
            launchWorker(context)
            return
        }
        if (attempt >= SERVICE_WAIT_MAX_ATTEMPTS) {
            logManager?.log("启动失败", "等待服务超时，任务跳过")
            val cb = callback
            cleanup()
            cb?.onTaskError("无障碍服务连接超时，请重新打开应用")
            return
        }
        mainHandler.postDelayed({ waitForServiceAndRun(context, attempt + 1) }, SERVICE_WAIT_STEP_MS)
    }

    private fun launchWorker(context: Context) {
        acquireWakeLock(context)

        val thread = HandlerThread("aishangban-worker").apply { start() }
        workerThread = thread
        val handler = Handler(thread.looper)
        workerHandler = handler
        handler.post { runTask() }
    }

    private fun acquireWakeLock(context: Context) {
        runCatching {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            @Suppress("Deprecation")
            val lock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "aishangban:task"
            )
            lock.acquire(WAKE_LOCK_TIMEOUT_MS)
            wakeLock = lock
        }.onFailure { logManager?.log("唤醒锁失败", it.message ?: "") }
    }

    private fun runTask() {
        val service = AutoAccessibilityService.getInstance()
        if (service == null) {
            postError("无障碍服务已断开")
            cleanup()
            return
        }
        try {
            service.wakeAndUnlock()
            sleepSafely(800)
            service.dismissKeyguard()
            sleepSafely(500)
            logManager?.log("开始执行", "第1个坐标")
            executeStep(0)
        } catch (t: Throwable) {
            postError("任务异常: ${t.message ?: ""}")
            cleanup()
        }
    }

    private fun sleepSafely(millis: Long) {
        runCatching { Thread.sleep(millis) }
    }

    private fun executeStep(index: Int) {
        if (!running) return

        if (index >= steps.size) {
            logManager?.log("任务完成", "所有坐标已点击")
            mainHandler.post { callback?.onTaskFinished(); cleanup() }
            return
        }

        val (stepNum, point, delayMs) = steps[index]
        logManager?.log("点击 $stepNum", "坐标(${point.x},${point.y})")
        mainHandler.post { if (running) callback?.onStepStart(stepNum) }

        val clicked = AutoAccessibilityService.performClick(point.x, point.y)
        if (clicked) {
            mainHandler.post { if (running) callback?.onStepSuccess(stepNum) }
        } else {
            logManager?.log("点击失败", "图标#$stepNum")
            postError("点击图标#$stepNum 失败")
        }

        if (!running) return
        if (index + 1 < steps.size) {
            val nextDelay = steps[index + 1].third
            logManager?.log("调度", "#${steps[index + 1].first} ${nextDelay / 1000}秒后执行")
            workerHandler?.postDelayed({ executeStep(index + 1) }, delayMs)
        } else {
            logManager?.log("完成", "所有步骤已执行")
            mainHandler.post { callback?.onTaskFinished(); cleanup() }
        }
    }

    private fun postError(message: String) {
        mainHandler.post { if (running) callback?.onTaskError(message) }
    }

    fun stop() {
        if (!running) return
        logManager?.log("任务停止", "用户手动停止")
        cleanup()
    }

    private fun cleanup() {
        running = false
        runCatching {
            releaseWakeLock()
            workerHandler?.removeCallbacksAndMessages(null)
            workerThread?.quitSafely()
        }
        workerHandler = null
        workerThread = null
        mainHandler.removeCallbacksAndMessages(null)
        if (activeInstance === this) activeInstance = null
    }

    private fun releaseWakeLock() {
        runCatching {
            wakeLock?.let { if (it.isHeld) it.release() }
        }
        wakeLock = null
    }

    companion object {
        private const val MIN_STEPS = 2
        const val MIN_STEPS_FOR_TASK = 2
        private const val WAKE_LOCK_TIMEOUT_MS = 600_000L
        /** 服务重连等待：每 500ms 检查一次，最多约 10 秒。 */
        private const val SERVICE_WAIT_STEP_MS = 500L
        private const val SERVICE_WAIT_MAX_ATTEMPTS = 20

        @Volatile
        private var activeInstance: TaskManager? = null

        fun startNew(context: Context, steps: List<Triple<Int, ClickPoint, Long>>, callback: TaskCallback) {
            stopActive()
            val manager = TaskManager()
            activeInstance = manager
            manager.start(context, steps, callback)
        }

        fun stopActive() {
            activeInstance?.stop()
            activeInstance = null
        }
    }
}
