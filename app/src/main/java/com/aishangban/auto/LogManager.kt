package com.aishangban.auto

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 执行日志管理。
 *
 * 闪退修复点 #2：
 * 原版 log() 直接调用 appendText，未捕获 IOException；磁盘满、文件被占用或
 * 目录不可写时会把异常抛到调用方（后台任务线程），导致任务中断甚至进程崩溃。
 * 现在所有文件读写都用 runCatching 包裹，日志失败不影响主流程。
 * 同时加锁，避免闹钟线程与工作线程同时写文件造成内容错乱。
 */
class LogManager(context: Context) {

    private val logFile: File = File(context.filesDir, "logs/execution.log")
    private val lock = Any()

    init {
        runCatching { logFile.parentFile?.mkdirs() }
    }

    fun log(event: String, detail: String = "") {
        synchronized(lock) {
            runCatching {
                val time = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                val suffix = if (detail.isEmpty()) "" else " - $detail"
                logFile.appendText("[$time] $event$suffix\n")
                trimLogLocked()
            }
        }
    }

    fun readLogs(): List<String> = synchronized(lock) {
        runCatching {
            if (!logFile.exists()) return@synchronized listOf("暂无执行记录")
            val lines = logFile.readLines()
            if (lines.isEmpty()) listOf("暂无执行记录") else lines
        }.getOrDefault(listOf("日志读取失败"))
    }

    fun clear() {
        synchronized(lock) {
            runCatching {
                logFile.delete()
                logFile.parentFile?.mkdirs()
            }
        }
    }

    private fun trimLogLocked() {
        runCatching {
            val lines = logFile.readLines()
            if (lines.size > MAX_LINES) {
                val tail = lines.takeLast(MAX_LINES).joinToString("\n")
                logFile.writeText("$tail\n")
            }
        }
    }

    private companion object {
        const val MAX_LINES = 500
    }
}
