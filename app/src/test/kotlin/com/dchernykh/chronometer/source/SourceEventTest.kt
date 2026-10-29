package com.dchernykh.chronometer.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SourceEventTest {
    private val now = 1_761_725_400_000L

    @Test
    fun aReadOrABreakWithAPlausibleTimeOrNoneIsUsable() {
        assertNull(SourceEvent.ChipRead("E280", now).problem())
        assertNull(SourceEvent.ChipRead("E280").problem())
        assertNull(SourceEvent.BeamBreak(1, now).problem())
        assertNull(SourceEvent.Status("connected").problem())
    }

    @Test
    fun aTimeFromADeviceClockNeverSetOrPast2099IsNot() {
        val uptime = 3_600_000L
        assertEquals(EventProblem.TIME_OUT_OF_RANGE, SourceEvent.ChipRead("E280", uptime).problem())
        assertEquals(EventProblem.TIME_OUT_OF_RANGE, SourceEvent.BeamBreak(1, LATEST_EVENT_MS).problem())
        assertNull(SourceEvent.BeamBreak(1, EARLIEST_EVENT_MS).problem())
    }

    @Test
    fun aReadWithoutAChipIdIsNot() {
        assertEquals(EventProblem.NO_CHIP, SourceEvent.ChipRead("  ", now).problem())
        assertEquals(EventProblem.NO_CHIP, SourceEvent.ChipRead("--- .", now).problem())
    }
}
