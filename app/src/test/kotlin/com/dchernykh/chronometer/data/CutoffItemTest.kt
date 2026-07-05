package com.dchernykh.chronometer.data

import org.junit.Assert.assertEquals
import org.junit.Test

class CutoffItemTest {
    @Test
    fun nextLapItem() {
        val cutoff = cutoff(CutoffEvent.NEXT_LAP)
        assertEquals("42#1 2:3:4.005#nextLap#", cutoff.toItem())
    }

    @Test
    fun disqualifiedItem() {
        val cutoff = cutoff(CutoffEvent.DSQ)
        assertEquals("42#1 2:3:4.005#DSQ#", cutoff.toItem())
    }

    @Test
    fun finishItem() {
        val cutoff = cutoff(CutoffEvent.FINISH)
        assertEquals("42#1 2:3:4.005#finish#", cutoff.toItem())
    }

    private fun cutoff(event: String) =
        CutoffEntity(number = "42", timeStr = "1 2:3:4.005", event = event, createdAt = 0)
}
