package com.dchernykh.chronometer.source

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SourceRunnerTest {
    private fun TestScope.runner(source: CutoffSource) = SourceRunner(source) { testScheduler.currentTime }

    /** The pauses between a source's starts, in virtual milliseconds. */
    private fun pauses(starts: List<Long>) = starts.zipWithNext { a, b -> b - a }

    @Test
    fun aFailingSourceIsStartedAgainAfterAPauseGrowingTo30Seconds() =
        runTest {
            val starts = mutableListOf<Long>()
            val runner =
                runner {
                    flow {
                        starts += testScheduler.currentTime
                        error("no reader")
                    }
                }
            backgroundScope.launch { runner.run {} }
            advanceTimeBy(1_000L + 2_000 + 4_000 + 8_000 + 16_000 + 30_000 + 30_000 + 1)
            runCurrent()
            assertEquals(listOf(1_000L, 2_000, 4_000, 8_000, 16_000, 30_000, 30_000), pauses(starts))
            assertEquals(SourceState.Restarting(30), runner.status.value.state)
            assertEquals(SourceError.Failed("no reader"), runner.status.value.lastError)
        }

    @Test
    fun aRunOf30SecondsBringsThePauseBackTo1Second() =
        runTest {
            val starts = mutableListOf<Long>()
            val runner =
                runner {
                    flow {
                        starts += testScheduler.currentTime
                        if (starts.size == 3) kotlinx.coroutines.delay(30_000)
                        error("lost")
                    }
                }
            backgroundScope.launch { runner.run {} }
            advanceTimeBy(1_000L + 2_000 + 30_000 + 1_000 + 1)
            runCurrent()
            assertEquals(listOf(1_000L, 2_000, 31_000), pauses(starts))
        }

    @Test
    fun aSourceThrowingAnErrorFailsItsRunAndStartsAgain() =
        runTest {
            var starts = 0
            val runner =
                runner {
                    flow<SourceEvent> {
                        starts++
                        TODO("LLRP reader")
                    }
                }
            backgroundScope.launch { runner.run {} }
            advanceTimeBy(1_000L + 1)
            runCurrent()
            assertEquals(2, starts)
            assertEquals(
                SourceError.Failed("An operation is not implemented: LLRP reader"),
                runner.status.value.lastError,
            )
        }

    @Test
    fun usableEventsGoOnInOrderAndInvalidOnesAreCounted() =
        runTest {
            val good = SourceEvent.BeamBreak(1, 1_761_725_400_000)
            val runner =
                runner {
                    flow {
                        emit(good)
                        emit(SourceEvent.BeamBreak(2, 5))
                        emit(SourceEvent.ChipRead(" "))
                        emit(SourceEvent.Error("antenna 2 lost"))
                        emit(SourceEvent.Status("connected"))
                        emit(good.copy(seq = 3))
                        awaitCancellation()
                    }
                }
            val got = mutableListOf<SourceEvent>()
            val job = backgroundScope.launch { runner.run { got += it } }
            runCurrent()
            assertEquals(listOf<SourceEvent>(good, good.copy(seq = 3)), got)
            assertEquals(2, runner.status.value.invalidEvents)
            assertEquals(SourceError.Device("antenna 2 lost"), runner.status.value.lastError)
            assertEquals(SourceState.Running, runner.status.value.state)
            job.cancel()
            runCurrent()
            assertEquals(SourceState.Stopped, runner.status.value.state)
        }

    @Test
    fun aFatalErrorStartsTheSourceAgain() =
        runTest {
            var runs = 0
            val runner =
                runner {
                    flow {
                        runs++
                        emit(SourceEvent.Error("reader rebooted", fatal = true))
                    }
                }
            backgroundScope.launch { runner.run {} }
            advanceTimeBy(1_001)
            runCurrent()
            assertEquals(2, runs)
            assertEquals(SourceError.Device("reader rebooted"), runner.status.value.lastError)
        }
}
