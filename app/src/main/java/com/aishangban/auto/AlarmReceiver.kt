package com.aishangban.auto

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.Random

/**
 * 闹钟接收器与调度器。
 *
 * 闪退修复点 #6：
 * 1) setAlarmClock 在 Android 12+ 需要精确闹钟权限，未授权时系统会抛
 *    SecurityException（这是“偶尔闪退”的高频原因）。这里先检查 canScheduleExactAlarms，
 *    并用 runCatching 兜底，权限缺失时只记录日志而不崩溃；
 * 2) 取消与设置闹钟的 PendingIntent 使用完全一致的 flag（FLAG_IMMUTABLE），
 *    原版两处 flag 不同会导致 cancel 失败、重复闹钟叠加；
 * 3) onReceive 中 getAll 逻辑全部包 runCatching，单个时段异常不影响其它时段。
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        runCatching {
            val index = intent.getIntExtra(EXTRA_TIME_INDEX, -1)
            if (index < 0) return

            val log = LogManager(context)
            val steps = IconConfigManager(context).getEnabledStepsWithDelays()
            if (steps.size < TaskManager.MIN_STEPS_FOR_TASK) {
                log.log("闹钟触发", "时段 #${index + 1} 坐标点不足，跳过")
            } else {
                log.log("闹钟触发", "时段 #${index + 1}，${steps.size} 个坐标点 开始执行")
                TaskManager.startNew(context, steps, object : TaskManager.TaskCallback {
                    override fun onStepStart(step: Int) = log.log("开始点击", "图标 #$step")
                    override fun onStepSuccess(step: Int) = log.log("点击成功", "图标 #$step")
                    override fun onTaskError(message: String) = log.log("任务错误", message)
                })
            }
            scheduleOne(context, index, forceTomorrow = true)
        }.onFailure {
            runCatching { LogManager(context).log("闹钟处理异常", it.message ?: "") }
        }
    }

    companion object {
        const val EXTRA_TIME_INDEX = "time_index"
        private const val MAX_LOOKAHEAD_DAYS = 365

        /** 重新计算并安排所有已保存时间段的闹钟。 */
        fun scheduleAlarms(context: Context) {
            cancelAlarms(context)
            val prefs = Prefs(context)
            for (i in 0 until prefs.timeCount) scheduleOne(context, i, forceTomorrow = false)
        }

        /** 安排单个时间段的下一次闹钟：在 [开始, 结束) 区间内随机取一个时间点。 */
        fun scheduleOne(context: Context, index: Int, forceTomorrow: Boolean) {
            runCatching {
                val prefs = Prefs(context)
                val range = prefs.getRange(index) ?: return
                if (!range.isValid) return

                val rangeSeconds = range.endTotalSeconds - range.startTotalSeconds
                val target = range.startTotalSeconds + Random().nextInt(rangeSeconds)
                val hour = target / 3600
                val minute = (target % 3600) / 60
                val second = target % 60

                LogManager(context).log(
                    "闹钟计算",
                    "范围=${range.displayText} 目标=" +
                        "%02d:%02d:%02d".format(hour, minute, second)
                )

                val calendar = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, hour)
                    set(Calendar.MINUTE, minute)
                    set(Calendar.SECOND, second)
                    set(Calendar.MILLISECOND, 0)
                }
                if (forceTomorrow || calendar.before(Calendar.getInstance())) {
                    calendar.add(Calendar.DAY_OF_YEAR, 1)
                }

                val config = IconConfigManager(context)
                var guard = MAX_LOOKAHEAD_DAYS
                while (!config.isWeekdayEnabled(calendar.get(Calendar.DAY_OF_WEEK)) && guard-- > 0) {
                    calendar.add(Calendar.DAY_OF_YEAR, 1)
                }

                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
                    ?: return
                if (!canScheduleExact(alarmManager)) {
                    LogManager(context).log("闹钟未安排", "缺少精确闹钟权限，请在系统设置中开启")
                    return
                }

                val intent = Intent(context, AlarmReceiver::class.java).apply {
                    putExtra(EXTRA_TIME_INDEX, index)
                }
                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    index,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                alarmManager.setAlarmClock(
                    AlarmManager.AlarmClockInfo(calendar.timeInMillis, pendingIntent),
                    pendingIntent
                )

                val label = if (forceTomorrow) "下次执行" else "首次执行"
                val time = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
                    .format(Date(calendar.timeInMillis))
                LogManager(context).log(label, time)
            }.onFailure {
                runCatching { LogManager(context).log("闹钟安排异常", it.message ?: "") }
            }
        }

        /** 取消所有已安排的闹钟。flag 必须与 scheduleOne 完全一致。 */
        fun cancelAlarms(context: Context) {
            runCatching {
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
                    ?: return
                for (i in 0 until MAX_ALARM_SLOTS) {
                    val intent = Intent(context, AlarmReceiver::class.java).apply {
                        putExtra(EXTRA_TIME_INDEX, i)
                    }
                    val pendingIntent = PendingIntent.getBroadcast(
                        context,
                        i,
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    alarmManager.cancel(pendingIntent)
                }
            }
        }

        private fun canScheduleExact(alarmManager: AlarmManager): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) alarmManager.canScheduleExactAlarms()
            else true

        private const val MAX_ALARM_SLOTS = 100
    }
}
