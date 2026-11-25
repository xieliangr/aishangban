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

    /**
     * 首次安装时预置默认坐标（步骤 1~6），写入一次后打标记，
     * 之后用户的任何修改或清空都不会被默认值覆盖。
     */
    fun ensureDefaults() {
        if (prefs.getBoolean(KEY_DEFAULTS_INITIALIZED, false)) return
        val editor = prefs.edit()
        DEFAULT_POINTS.forEach { p ->
            editor.putString(pointKey(p.step, "x"), p.x.toString())
            editor.putString(pointKey(p.step, "y"), p.y.toString())
            editor.putString(pointKey(p.step, "delay"), p.delaySeconds.toString())
            editor.putBoolean(enabledKey(p.step), true)
        }
        editor.putBoolean(KEY_DEFAULTS_INITIALIZED, true)
        editor.apply()
    }

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
        private const val KEY_DEFAULTS_INITIALIZED = "defaults_initialized_v1"

        /** 默认坐标：步骤号 / X / Y / 延迟秒。步骤 7、8 留空由用户自行填写。 */
        private data class DefaultPoint(val step: Int, val x: Int, val y: Int, val delaySeconds: Int)
        private val DEFAULT_POINTS = listOf(
            DefaultPoint(1, 533, 2323, 10),
            DefaultPoint(2, 533, 2323, 10),
            DefaultPoint(3, 150, 1230, 30),
            DefaultPoint(4, 750, 1630, 30),
            DefaultPoint(5, 335, 2323, 10),
            DefaultPoint(6, 335, 2323, 10),
        )

        private fun pointKey(step: Int, field: String) = "point_${step}_$field"
        private fun enabledKey(step: Int) = "icon_${step}_enabled"
        private fun weekdayKey(day: Int) = "weekday_${day}_enabled"

        private fun safeInt(value: String?): Int? =
            value?.trim()?.toIntOrNull()?.takeIf { it in -100000..100000 }
    }
}
