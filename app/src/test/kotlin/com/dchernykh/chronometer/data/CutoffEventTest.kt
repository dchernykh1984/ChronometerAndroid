package com.dchernykh.chronometer.data

import org.junit.Assert.assertEquals
import org.junit.Test

class CutoffEventTest {
    @Test
    fun regularPressRecordsNextLapByDefault() {
        assertEquals("nextLap", CutoffEvent.lapEvent(finishMode = false))
    }

    @Test
    fun finishModeRecordsFinish() {
        assertEquals("finish", CutoffEvent.lapEvent(finishMode = true))
    }
}
