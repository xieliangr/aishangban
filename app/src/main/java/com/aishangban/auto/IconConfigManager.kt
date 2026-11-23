package com.aishangban.auto

import android.content.Context
import android.content.SharedPreferences

/**
 * 图标坐标与开关配置。
 *
 * 闪退修复点 #3：
 * 原版从 SharedPreferences 读取坐标后直接 toInt()，一旦本地数据损坏或旧版本残留
 * 非法字符串会抛 NumberFormatException。这里统一使用安全解析并校验范围。
 * 另外偏好设置文件名从 aixuexi 改为 aishangban，作为新版本的独立命名空间。
 */
class IconConfigManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getPoint(step: Int): ClickPoint? {
        val x = safeInt(prefs.getString(pointKey(step, "x"), null))
        val y = safeInt(prefs.getString(pointKey(step, "y"), null))
        return if (x != null && y != null) ClickPoint(x, y) else null
    }

    fun setPoint(step: Int, x: Int, y: Int) {
        prefs.edit()
            .putString(pointKey(step, "x"), x.toString())
            .putString(pointKey(step, "y"), y.toString())
            .apply()
    }

    fun removePoint(step: Int) {
        prefs.edit()
            .remove(pointKey(step, "x"))
            .remove(pointKey(step, "y"))
            .remove(pointKey(step, "delay"))
            .apply()
    }

    fun getPointDelay(step: Int): Int =
        safeInt(prefs.getString(pointKey(step, "delay"), null))?.coerceAtLeast(1) ?: DEFAULT_DELAY

    fun setPointDelay(step: Int, delaySeconds: Int) {
        prefs.edit()
            .putString(pointKey(step, "delay"), delaySeconds.coerceAtLeast(1).toString())
            .apply()
    }

    fun isEnabled(step: Int): Boolean = prefs.getBoolean(enabledKey(step), false)

    fun setEnabled(step: Int, enabled: Boolean) {
        prefs.edit().putBoolean(enabledKey(step), enabled).apply()
    }

    fun setEnabledBatch(states: Map<Int, Boolean>) {
        val editor = prefs.edit()
        states.forEach { (step, enabled) -> editor.putBoolean(enabledKey(step), enabled) }
        editor.apply()
    }

    fun isWeekdayEnabled(dayOfWeek: Int): Boolean =
        prefs.getBoolean(weekdayKey(dayOfWeek), true)

    fun setWeekdayEnabled(dayOfWeek: Int, enabled: Boolean) {
        prefs.edit().putBoolean(weekdayKey(dayOfWeek), enabled).apply()
    }

    fun setWeekdaysBatch(states: Map<Int, Boolean>) {
        val editor = prefs.edit()
        states.forEach { (day, enabled) -> editor.putBoolean(weekdayKey(day), enabled) }
        editor.apply()
    }

    /** 返回需要执行的有序步骤：(步骤号, 坐标, 延迟毫秒)。 */
    fun getEnabledStepsWithDelays(): List<Triple<Int, ClickPoint, Long>> {
        val result = mutableListOf<Triple<Int, ClickPoint, Long>>()
        for (step in 1..MAX_STEPS) {
            if (!isEnabled(step)) continue
            val point = getPoint(step) ?: continue
            result.add(Triple(step, point, getPointDelay(step) * 1000L))
        }
        return result
    }

    companion object {
        private const val PREFS_NAME = "aishangban"
        private const val DEFAULT_DELAY = 10
        const val MAX_STEPS = 8

        private fun pointKey(step: Int, field: String) = "point_${step}_$field"
        private fun enabledKey(step: Int) = "icon_${step}_enabled"
        private fun weekdayKey(day: Int) = "weekday_${day}_enabled"

        private fun safeInt(value: String?): Int? =
            value?.trim()?.toIntOrNull()?.takeIf { it in -100000..100000 }
    }
}
