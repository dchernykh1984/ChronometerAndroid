package com.dchernykh.chronometer.chips

import org.junit.Assert.assertEquals
import org.junit.Test

/** The cases ChronometerPython's tests/test_chips.py runs: one rule on both chronometers. */
class ChipMapTest {
    private fun line(
        bib: String,
        chipField: String,
        stages: String = "1",
    ) = "$bib#Rider#M40#3#$stages#1990#T#C##0 00:00:00.000#r1#$chipField#"

    @Test
    fun idsAreComparedUpperCaseLettersAndDigitsOnly() {
        assertEquals("E28011700000020A", normaliseChip(" e280-1170 0000.020a "))
        assertEquals("1", normaliseChip("\u0415" + "1"))
    }

    @Test
    fun aLineBindsEveryChipToItsBib() {
        assertEquals(listOf("A1" to "15", "A2" to "15"), chipBindings(line("15", "a1,A2,a-1")))
        assertEquals(listOf("A1" to "15", "B1" to "15"), chipBindings(line("15", "A1;B1")))
    }

    @Test
    fun aRelayBindsEachRiderToTheirStageBib() {
        assertEquals(
            listOf("A1" to "21", "A2" to "21", "B1" to "22"),
            chipBindings(line("21", "A1,A2;B1;;D1", stages = "3")),
        )
        assertEquals(
            listOf("A1" to "21", "B1" to "22", "C1" to "23"),
            chipBindings(line("21", "A1;B1;C1", stages = "3")),
        )
        assertEquals(listOf("A1" to "007", "B1" to "8"), chipBindings(line("007", "A1;B1", stages = "2")))
    }

    @Test
    fun stagesNeedANumericBibAndMoreThanOneStage() {
        assertEquals(listOf("A1" to "T21", "B1" to "T21"), chipBindings(line("T21", "A1;B1", stages = "3")))
        assertEquals(listOf("A1" to "21", "B1" to "21"), chipBindings(line("21", "A1;B1", stages = "1")))
        assertEquals(listOf("A1" to "21", "B1" to "21"), chipBindings(line("21", "A1;B1", stages = "+3")))
        // More stages than an Int holds: every rider still binds to bib + k.
        assertEquals(
            listOf("A1" to "21", "B1" to "22"),
            chipBindings(line("21", "A1;B1", stages = "99999999999999999999")),
        )
    }

    @Test
    fun linesWithoutABibOrAChipFieldBindNothing() {
        assertEquals(emptyList<Pair<String, String>>(), chipBindings(line("", "A1")))
        assertEquals(emptyList<Pair<String, String>>(), chipBindings(line(" NOT_PAID ", "A1")))
        assertEquals(emptyList<Pair<String, String>>(), chipBindings("15#Rider#M40#3#1#1990#T#C##0 00:00:00.000#r1#"))
        assertEquals(emptyList<Pair<String, String>>(), chipBindings("15"))
    }

    @Test
    fun theMapReportsAChipOnTwoBibsAndMapsItToNeither() {
        val map = ChipMap.of(listOf(line("15", "A1,X9"), line("16", "B1,x9"), line("15", "A1"), line("17", "")))
        assertEquals(mapOf("A1" to "15", "B1" to "16"), map.bibOf)
        assertEquals(mapOf("X9" to listOf("15", "16")), map.conflicts)
        assertEquals(2, map.bibs)
        assertEquals(1, map.unboundLines)
    }

    @Test
    fun anEmptyStartProtocolMapsNothing() {
        val map = ChipMap.of(emptyList())
        assertEquals(0, map.bibs)
        assertEquals(0, map.unboundLines)
    }
}
