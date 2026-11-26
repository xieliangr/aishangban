package com.aishangban.auto

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.os.PowerManager
import android.view.accessibility.AccessibilityEvent

/**
 * 无障碍服务：负责执行点击手势、唤醒屏幕与解锁。
 *
 * 闪退修复点 #4：
 * 1) instance 使用 @Volatile，避免后台任务线程读取到过期实例造成“服务实例为空”；
 * 2) performClick / wakeAndUnlock / dismissKeyguard 全部捕获异常，
 *    手势分发失败（服务被系统断开、坐标越界等）不再抛到任务线程；
 * 3) WakeLock 用 try/finally 确保释放，避免忘记释放导致系统限制后续唤醒。
 */
class AutoAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        // 进程重启后系统可能延迟重新绑定服务；连接成功立即通知界面刷新状态
        AccessibilityUtils.notifyServiceStateChanged(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) instance = null
        AccessibilityUtils.notifyServiceStateChanged(this)
        super.onDestroy()
    }

    fun wakeAndUnlock() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                performGlobalAction(GLOBAL_ACTION_WAKE)
                return
            }
            val pm = getSystemService(POWER_SERVICE) as? PowerManager ?: return
            if (!pm.isInteractive) {
                @Suppress("Deprecation")
                val wakeLock = pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "aishangban:wake"
                )
                try {
                    wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS)
                } finally {
                    if (wakeLock.isHeld) wakeLock.release()
                }
            }
        }
    }

    fun dismissKeyguard() {
        runCatching {
            val action = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                GLOBAL_ACTION_DISMISS_KEYGUARD_SECURE
            } else {
                GLOBAL_ACTION_DISMISS_KEYGUARD
            }
            performGlobalAction(action)
        }
    }

    companion object {
        private const val WAKE_LOCK_TIMEOUT_MS = 3_000L
        private const val GLOBAL_ACTION_WAKE = 16
        private const val GLOBAL_ACTION_DISMISS_KEYGUARD = 15
        private const val GLOBAL_ACTION_DISMISS_KEYGUARD_SECURE = 18

        @Volatile
        private var instance: AutoAccessibilityService? = null

        val isRunning: Boolean get() = instance != null

        fun getInstance(): AutoAccessibilityService? = instance

        fun performClick(x: Int, y: Int): Boolean {
            val service = instance ?: return false
            return runCatching {
                val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
                val stroke = GestureDescription.StrokeDescription(path, 0L, CLICK_DURATION_MS)
                val gesture = GestureDescription.Builder().addStroke(stroke).build()
                service.dispatchGesture(gesture, null, null)
            }.getOrDefault(false)
        }

        private const val CLICK_DURATION_MS = 100L
    }
}
