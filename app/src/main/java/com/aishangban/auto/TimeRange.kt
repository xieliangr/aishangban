package com.aishangban.auto

/** 一个执行时间段（分钟粒度，24 小时制）。 */
data class TimeRange(
    val startHour: Int,
    val startMinute: Int,
    val endHour: Int,
    val endMinute: Int
) {
    val startTotalSeconds: Int get() = startHour * 3600 + startMinute * 60
    val endTotalSeconds: Int get() = endHour * 3600 + endMinute * 60

    val isValid: Boolean
        get() = startHour in 0..23 && endHour in 0..23 &&
            startMinute in 0..59 && endMinute in 0..59 &&
            endTotalSeconds > startTotalSeconds

    val displayText: String
        get() = "%02d:%02d → %02d:%02d".format(startHour, startMinute, endHour, endMinute)
}
