package com.dchernykh.chronometer.source

import com.dchernykh.chronometer.chips.ChipMap
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

private const val BASE_MS = 1_767_261_600_000L

@OptIn(ExperimentalCoroutinesApi::class)
class TestChipsSourceTest {
    /** The reads, split into passes where 3 s went by without one. */
    private suspend fun TestScope.passes(map: ChipMap): List<List<SourceEvent.ChipRead>> {
        val reads =
            TestChipsSource({ map }, Random(3)) { BASE_MS + testScheduler.currentTime }
                .events()
                .take(80)
                .toList()
                .map { it as SourceEvent.ChipRead }
        val passes = mutableListOf<MutableList<SourceEvent.ChipRead>>()
        for ((i, read) in reads.withIndex()) {
            if (i == 0 || read.timeMs!! - reads[i - 1].timeMs!! >= 3_000) {
                passes += mutableListOf<SourceEvent.ChipRead>()
            }
            passes.last() += read
        }
        assertTrue(reads.all { it.problem() == null && it.antenna in setOf("1", "2", "3", "4") })
        assertTrue(reads.all { it.rssi!! in -75.0..-45.0 && it.reader == "test" })
        return passes.dropLast(1)
    }

    @Test
    fun ridersPassInTurnReadTwoToFourTimesByTheirChips() =
        runTest {
            val passes = passes(ChipMap(bibOf = linkedMapOf("A1" to "15", "A2" to "15", "B1" to "16")))
            assertTrue(passes.all { it.size in 2..4 && it.last().timeMs!! - it.first().timeMs!! < 1_000 })
            assertTrue(passes[0].map { it.chip }.toSet() in listOf(setOf("A1"), setOf("A2"), setOf("A1", "A2")))
            assertEquals(setOf("B1"), passes[1].map { it.chip }.toSet())
            assertEquals(setOf(UNKNOWN_TEST_CHIP), passes[9].map { it.chip }.toSet())
            // The unknown chip's pass counts in the turn, as on the desktop: the 12th is B again.
            assertEquals(setOf("B1"), passes[11].map { it.chip }.toSet())
        }

    @Test
    fun withoutAMapOnlyTheUnknownChipIsRead() =
        runTest {
            assertEquals(setOf(UNKNOWN_TEST_CHIP), passes(ChipMap()).flatten().map { it.chip }.toSet())
        }
}
