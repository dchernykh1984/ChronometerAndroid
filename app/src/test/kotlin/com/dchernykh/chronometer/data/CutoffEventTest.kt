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

    @Test
    fun dsqWithoutReasonIsBareDsq() {
        assertEquals("DSQ", CutoffEvent.dsq())
        assertEquals("DSQ", CutoffEvent.dsq("   "))
    }

    @Test
    fun dsqWithReasonIsAppended() {
        assertEquals("DSQ: cut the course", CutoffEvent.dsq("cut the course"))
    }

    @Test
    fun dsqReasonIsTrimmed() {
        assertEquals("DSQ: rude", CutoffEvent.dsq("  rude  "))
    }

    @Test
    fun dsqReasonHashIsStripped() {
        assertEquals("DSQ: a b", CutoffEvent.dsq("a#b"))
    }

    @Test
    fun dsqReasonLineBreaksAreStripped() {
        assertEquals("DSQ: l1 l2 l3", CutoffEvent.dsq("l1\nl2\rl3"))
    }

    @Test
    fun dsqReasonLosesEveryLineBreakPythonSplitsAt() {
        // FinishProtocolGenerator reads results.txt with str.splitlines(), which splits at each.
        "\u000B\u000C\u001C\u001D\u001E\u0085\u2028\u2029".forEach {
            assertEquals("U+${it.code.toString(16)}", "DSQ: fell off", CutoffEvent.dsq("fell${it}off"))
        }
    }
}
