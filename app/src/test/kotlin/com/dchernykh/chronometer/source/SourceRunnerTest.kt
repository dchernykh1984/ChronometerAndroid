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
    fun aBoardsLastingTroubleEndsWhenAllIsWellButANoticeStays() =
        runTest {
            val said = mutableListOf<SourceError?>()
            val taken = mutableListOf<SourceEvent>()
            lateinit var board: SourceRunner
            board =
                runner {
                    flow {
                        for (event in listOf(
                            SourceEvent.Board(BoardTrouble.NotFound),
                            SourceEvent.Board(null),
                            SourceEvent.Board(BoardTrouble.BreaksMissing(6, 7)),
                            SourceEvent.Board(null),
                            SourceEvent.Board(BoardTrouble.Blocked),
                        )) {
                            emit(event)
                            said += board.status.value.lastError
                        }
                        awaitCancellation()
                    }
                }
            backgroundScope.launch { board.run { taken += it } }
            runCurrent()
            assertEquals(
                listOf(
                    SourceError.Board(BoardTrouble.NotFound),
                    null, // the board found: said no more
                    SourceError.Board(BoardTrouble.BreaksMissing(6, 7)),
                    SourceError.Board(BoardTrouble.BreaksMissing(6, 7)), // what was lost stays said
                    SourceError.Board(BoardTrouble.Blocked),
                ),
                said,
            )
            assertEquals(emptyList<SourceEvent>(), taken) // a board's word is nothing to store
        }

    @Test
    fun eachBoardPickedHasItsOwnStateAndItsNoticesAreSaidWithItsName() =
        runTest {
            val runner =
                runner {
                    flow {
                        emit(SourceEvent.Board(BoardTrouble.NotInRange("AA"), board = "beam-7c1c"))
                        emit(SourceEvent.Board(null, board = "beam-b6b0"))
                        emit(SourceEvent.Board(BoardTrouble.BreaksMissing(6, 7), board = "beam-b6b0"))
                        emit(SourceEvent.Board(BoardTrouble.Blocked, board = "beam-b6b0"))
                        emit(SourceEvent.Board(null, board = "beam-7c1c")) // back: its trouble ends, no other's
                        awaitCancellation()
                    }
                }
            backgroundScope.launch { runner.run {} }
            runCurrent()
            val status = runner.status.value
            assertEquals(mapOf("beam-7c1c" to null, "beam-b6b0" to BoardTrouble.Blocked), status.boards)
            assertEquals(listOf("beam-7c1c", "beam-b6b0"), status.boards.keys.toList()) // in the order picked
            assertEquals(SourceError.Board(BoardTrouble.BreaksMissing(6, 7), "beam-b6b0"), status.lastError)
        }

    @Test
    fun aNoticeALastingTroubleHidIsSaidAgainOnceThatTroubleEnds() =
        runTest {
            val said = mutableListOf<SourceError?>()
            lateinit var board: SourceRunner
            board =
                runner {
                    flow {
                        for (trouble in listOf(BoardTrouble.BreaksMissing(6, 7), BoardTrouble.Blocked, null)) {
                            emit(SourceEvent.Board(trouble))
                            said += board.status.value.lastError
                        }
                        awaitCancellation()
                    }
                }
            backgroundScope.launch { board.run {} }
            runCurrent()
            val lost = SourceError.Board(BoardTrouble.BreaksMissing(6, 7))
            // Riders standing in the beam just after: the breaks lost are said again after them.
            assertEquals(listOf(lost, SourceError.Board(BoardTrouble.Blocked), lost), said)
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
