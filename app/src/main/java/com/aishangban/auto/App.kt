package com.aishangban.auto

import android.app.Application
import android.content.Context
import android.os.Process
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

/**
 * 应用入口。
 *
 * 闪退修复点 #1：
 * 原版把 Thread.setDefaultUncaughtExceptionHandler 写在 MainActivity.onCreate 中，
 * 且处理器只写日志、既不恢复默认处理器也不结束进程。结果是主线程 Looper 死掉后
 * 界面卡死/白屏（表现为“闪退”），并且每次重建 Activity 都会重复安装处理器。
 * 现在改为在 Application 中只安装一次，并在写日志后交回系统默认处理器，
 * 让进程被正确回收，同时保证写日志本身永远不会二次抛异常。
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

            // 交回系统默认处理器，确保进程正常结束，避免主线程死循环卡死
            val previous = defaultHandler
            if (previous != null && previous !is CrashHandler) {
                runCatching { previous.uncaughtException(thread, throwable) }
            }
            Process.killProcess(Process.myPid())
            exitProcess(10)
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
