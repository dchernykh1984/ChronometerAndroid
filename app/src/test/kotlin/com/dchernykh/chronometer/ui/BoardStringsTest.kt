package com.dchernykh.chronometer.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The main screen gives a source's error two lines: a phone fits some 80 characters there. */
private const val TWO_LINES = 80

private val BOARD_STRING = Regex("""<string name="(source_board_[a-z_]+)">([^<]*)</string>""")

/**
 * A beam board's troubles, as the main screen says them: whole in two lines, so the
 * end of one -- what the referee is to do -- is never cut off, and in all three languages.
 */
class BoardStringsTest {
    private fun strings(folder: String): Map<String, String> =
        BOARD_STRING
            .findAll(File("src/main/res/$folder/strings.xml").readText())
            .associate { it.groupValues[1] to it.groupValues[2] }

    @Test
    fun eachTroubleFitsTheTwoLinesTheScreenGivesItInEveryLanguage() {
        for (folder in listOf("values", "values-ru", "values-kk")) {
            val long = strings(folder).filterValues { it.length > TWO_LINES }
            assertTrue("$folder: $long", long.isEmpty())
        }
    }

    @Test
    fun everyLanguageSaysEveryTrouble() {
        val english = strings("values").keys
        assertTrue(english.size >= 14)
        assertEquals(english, strings("values-ru").keys)
        assertEquals(english, strings("values-kk").keys)
    }
}
