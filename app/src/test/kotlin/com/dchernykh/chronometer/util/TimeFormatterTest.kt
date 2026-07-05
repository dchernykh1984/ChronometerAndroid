package com.dchernykh.chronometer.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.TimeZone

class TimeFormatterTest {
    @Test
    fun formatsDaysHoursMinutesSecondsMillis() {
        withUtc {
            val ms = 86_400_000L + (2 * 3_600_000L) + (3 * 60_000L) + (4 * 1_000L) + 5
            assertEquals("1 2:3:4.005", TimeFormatter.currentTimeString(ms))
        }
    }

    @Test
    fun padsMillisToThreeDigits() {
        withUtc {
            assertEquals("0 0:0:0.007", TimeFormatter.currentTimeString(7L))
        }
    }

    private fun withUtc(block: () -> Unit) {
        val previous = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            block()
        } finally {
            TimeZone.setDefault(previous)
        }
    }
}
