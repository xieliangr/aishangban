package com.aishangban.auto

import android.app.Application
import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 应用入口。
 *
 * 闪退修复点 #1：
 * 原版把 Thread.setDefaultUncaughtExceptionHandler 写在 MainActivity.onCreate 中，
 * 且每次重建 Activity 都会重复安装处理器。现在改为在 Application 中只安装一次，
 * 并保证写日志本身永远不会二次抛异常。
 *
 * v1.0.3 进一步修复：处理器不再无条件 killProcess。后台线程（定时点击任务）的异常
 * 只记录日志、不结束进程，避免把无障碍服务一起杀死导致「第二天程序退出、无障碍断开」。
 * 只有主线程异常才交回系统默认处理器处理。
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        installCrashHandler()
    }

    private fun installCrashHandler() {
        val current = Thread.getDefaultUncaughtExceptionHandler()
        if (current is CrashHandler) return // 防止重复安装
        Thread.setDefaultUncaughtExceptionHandler(CrashHandler(applicationContext, current))
    }

    class CrashHandler(
        private val appContext: Context,
        private val defaultHandler: Thread.UncaughtExceptionHandler?
    ) : Thread.UncaughtExceptionHandler {

        override fun uncaughtException(thread: Thread, throwable: Throwable) {
            runCatching { writeCrashLog(thread, throwable) }

            // v1.0.3 关键修复：绝不主动 killProcess。
            //
            // 原实现不论哪个线程抛异常都 Process.killProcess + exitProcess，
            // 导致后台任务线程（HandlerThread）里的任何一次偶发异常都会把整个进程杀掉，
            // 无障碍服务随之销毁、系统配置虽在但实例丢失，用户下次打开就看到「未启动」，
            // 定时任务也随之中断——这正是「运行一次后第二天程序退出」的根因。
            // 旧版爱学习只写日志不杀进程，所以表现正常。
            //
            // 现在：
            // - 主线程异常：交回系统默认处理器，由系统按标准流程回收该进程（界面已不可用）；
            // - 后台线程异常：只记录日志后直接返回，线程结束但进程与无障碍服务保持存活。
            val isMainThread = thread == android.os.Looper.getMainLooper().thread
            val previous = defaultHandler
            if (isMainThread && previous != null && previous !is CrashHandler) {
                runCatching { previous.uncaughtException(thread, throwable) }
            }
            // 后台线程异常不往下传播，也不结束进程。
        }

        private fun writeCrashLog(thread: Thread, throwable: Throwable) {
            val dir = File(appContext.filesDir, "logs")
            if (!dir.exists()) dir.mkdirs()
            val stack = StringWriter().use { sw ->
                PrintWriter(sw).use { pw -> throwable.printStackTrace(pw) }
                sw.toString()
            }
            val time = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            val line = "[$time] 崩溃 - ${throwable.message}\n线程: ${thread.name}\n$stack\n"
            File(dir, "execution.log").appendText(line)
        }
    }
}
