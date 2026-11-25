package com.aishangban.auto

import android.content.Context
import android.content.SharedPreferences

/**
 * 时间段配置的读写封装。
 *
 * 闪退修复点 #7：
 * 所有读取都带默认值与范围校验，避免损坏的本地数据导致
 * IllegalArgumentException / IndexOutOfBoundsException。
 */
class Prefs(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val timeCount: Int get() = prefs.getInt(KEY_TIME_COUNT, 0).coerceIn(0, MAX_TIMES)

    /** 精确闹钟权限引导是否已经展示过（避免每次打开都跳系统设置页）。 */
    val exactAlarmHintShown: Boolean
        get() = prefs.getBoolean(KEY_EXACT_ALARM_HINT_SHOWN, false)

    fun markExactAlarmHintShown() {
        prefs.edit().putBoolean(KEY_EXACT_ALARM_HINT_SHOWN, true).apply()
    }

    fun getRange(index: Int): TimeRange? {
        if (index !in 0 until timeCount) return null
        val sh = prefs.getInt("time_${index}_start_hour", -1)
        val sm = prefs.getInt("time_${index}_start_minute", -1)
        val eh = prefs.getInt("time_${index}_end_hour", -1)
        val em = prefs.getInt("time_${index}_end_minute", -1)
        val range = TimeRange(sh, sm, eh, em)
        return if (range.isValid) range else null
    }

    fun saveRanges(ranges: List<TimeRange>) {
        val editor = prefs.edit()
        editor.putInt(KEY_TIME_COUNT, ranges.size)
        ranges.forEachIndexed { i, r ->
            editor.putInt("time_${i}_start_hour", r.startHour)
            editor.putInt("time_${i}_start_minute", r.startMinute)
            editor.putInt("time_${i}_end_hour", r.endHour)
            editor.putInt("time_${i}_end_minute", r.endMinute)
        }
        // 清理旧数据，避免残留时间段被重复安排
        for (i in ranges.size until MAX_TIMES) {
            editor.remove("time_${i}_start_hour")
            editor.remove("time_${i}_start_minute")
            editor.remove("time_${i}_end_hour")
            editor.remove("time_${i}_end_minute")
        }
        editor.apply()
    }

    companion object {
        private const val PREFS_NAME = "aishangban"
        private const val KEY_TIME_COUNT = "time_count"
        private const val KEY_EXACT_ALARM_HINT_SHOWN = "exact_alarm_hint_shown"
        private const val MAX_TIMES = 50
    }
}
