package com.aishangban.auto

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 开机/应用更新后恢复已保存的闹钟。
 * 原版没有该接收器，设备重启后所有定时任务永久失效。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.LOCKED_BOOT_COMPLETED" ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            runCatching { AlarmReceiver.scheduleAlarms(context) }
        }
    }
}
