package com.dchernykh.chronometer.source

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

private const val BASE_MS = 1_767_261_600_000L

@OptIn(ExperimentalCoroutinesApi::class)
class TestBeamSourceTest {
    @Test
    fun breaksComeEvery5To20SecondsAndCountUp() =
        runTest {
            val breaks =
                TestBeamSource(Random(7)) { BASE_MS + testScheduler.currentTime }
                    .events()
                    .take(50)
                    .toList()
                    .map { it as SourceEvent.BeamBreak }
            assertEquals((1L..50L).toList(), breaks.map { it.seq })
            val gaps = (listOf(BASE_MS) + breaks.map { it.timeMs!! }).zipWithNext { a, b -> b - a }
            assertTrue(gaps.toString(), gaps.all { it in 5_000L..20_000L })
            assertTrue(breaks.all { it.problem() == null })
        }
}
