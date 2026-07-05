package com.dchernykh.chronometer.util

import java.util.TimeZone

/**
 * Formats a wall-clock instant as "D H:M:S.mmm" (days since the Unix epoch, then
 * hours:minutes:seconds.milliseconds of the day), in local standard time.
 *
 * Mirrors WindowsChronometerPython's `get_current_time` and the old SportTimer
 * Android app so every device feeds the server the same string format. The raw
 * (standard) UTC offset is used without a DST correction, matching the Python
 * default; Kazakhstan does not observe DST.
 */
object TimeFormatter {
    private const val MS_PER_DAY = 86_400_000L
    private const val MS_PER_HOUR = 3_600_000L
    private const val MS_PER_MINUTE = 60_000L
    private const val MS_PER_SECOND = 1_000L

    fun currentTimeString(nowMs: Long = System.currentTimeMillis()): String {
        val local = nowMs + TimeZone.getDefault().rawOffset
        val days = local / MS_PER_DAY
        var rem = local % MS_PER_DAY
        val hours = rem / MS_PER_HOUR
        rem %= MS_PER_HOUR
        val minutes = rem / MS_PER_MINUTE
        rem %= MS_PER_MINUTE
        val seconds = rem / MS_PER_SECOND
        val millis = (rem % MS_PER_SECOND).toString().padStart(3, '0')
        return "$days $hours:$minutes:$seconds.$millis"
    }
}
