package com.dchernykh.chronometer.io

import org.junit.Assert.assertEquals
import org.junit.Test

class RawFilesTest {
    @Test
    fun aSignalIsWrittenAsTheDesktopChronometerWritesIt() {
        assertEquals("-63", RawFiles.signal(-63.0))
        assertEquals("-58.5", RawFiles.signal(-58.5))
        assertEquals("-58.1235", RawFiles.signal(-58.123456))
        assertEquals("0", RawFiles.signal(0.0))
        assertEquals("", RawFiles.signal(null))
        assertEquals("", RawFiles.signal(Double.NaN))
    }

    @Test
    fun deviceTextLosesItsHashesAndLineBreaks() {
        assertEquals("a b c", RawFiles.field(" a#b\u2028c "))
    }
}
