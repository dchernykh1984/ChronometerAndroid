package com.dchernykh.chronometer.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun acceptsWellFormedTimeStrings() {
        assertTrue(TimeFormatter.isValidTimeString("1 2:3:4.005"))
        assertTrue(TimeFormatter.isValidTimeString("0 0:0:0.007"))
        assertTrue(TimeFormatter.isValidTimeString("20300 23:59:59.999"))
        // Single-digit hours/minutes/seconds ("9", not "09") are how the app writes them.
        assertTrue(TimeFormatter.isValidTimeString("20300 14:5:3.007"))
        // A leading-zero millisecond like "010" is a normal, valid value.
        assertTrue(TimeFormatter.isValidTimeString("20300 14:5:3.010"))
    }

    @Test
    fun rejectsMalformedOrOutOfRangeTimeStrings() {
        assertFalse(TimeFormatter.isValidTimeString(""))
        assertFalse(TimeFormatter.isValidTimeString("20300 14:5:3")) // no millis
        assertFalse(TimeFormatter.isValidTimeString("20300 14:5:3.07")) // millis not 3 digits
        assertFalse(TimeFormatter.isValidTimeString("14:5:3.007")) // no day part
        assertFalse(TimeFormatter.isValidTimeString("20300 24:0:0.000")) // hours > 23
        assertFalse(TimeFormatter.isValidTimeString("20300 12:60:0.000")) // minutes > 59
        assertFalse(TimeFormatter.isValidTimeString("20300 12:0:60.000")) // seconds > 59
        assertFalse(TimeFormatter.isValidTimeString("20300 aa:5:3.007")) // non-numeric
        assertFalse(TimeFormatter.isValidTimeString("20300 14:5:3.007 ")) // trailing space
        assertFalse(TimeFormatter.isValidTimeString("20300 14:5:3.0#7")) // '#' breaks the record
    }

    @Test
    fun currentTimeStringIsAlwaysValid() {
        withUtc {
            assertTrue(TimeFormatter.isValidTimeString(TimeFormatter.currentTimeString(123_456_789L)))
        }
    }

    @Test
    fun aTimeStringReadsBackAsTheMillisecondsItWasWrittenFrom() {
        withUtc {
            val ms = 20_389 * 86_400_000L + 11 * 3_600_000L + 35 * 60_000L + 29_615
            assertEquals(ms, TimeFormatter.millisOf(TimeFormatter.currentTimeString(ms)))
        }
        assertEquals(5L, TimeFormatter.millisOf("0 0:0:0.005"))
        assertNull(TimeFormatter.millisOf("0 24:0:0.000"))
        assertNull(TimeFormatter.millisOf("1 2:3:4"))
        // A day count whose milliseconds a Long cannot hold.
        assertNull(TimeFormatter.millisOf("106751991167 0:0:0.000"))
        assertEquals(106_751_991_166L * 86_400_000L, TimeFormatter.millisOf("106751991166 0:0:0.000"))
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
