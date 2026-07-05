package com.dchernykh.chronometer.data

import org.junit.Assert.assertEquals
import org.junit.Test

class CutoffItemTest {
    @Test
    fun normalCutoffItem() {
        val cutoff = CutoffEntity(number = "42", timeStr = "1 2:3:4.005", disqualified = false, createdAt = 0)
        assertEquals("42#1 2:3:4.005#", cutoff.toItem())
    }

    @Test
    fun disqualifiedItem() {
        val cutoff = CutoffEntity(number = "42", timeStr = "1 2:3:4.005", disqualified = true, createdAt = 0)
        assertEquals("42#1 2:3:4.005#DSQ#", cutoff.toItem())
    }
}
