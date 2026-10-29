package com.dchernykh.chronometer.source

import android.os.Looper
import com.dchernykh.chronometer.data.SettingsStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.Collections

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SourceControllerTest {
    private val store = SettingsStore(RuntimeEnvironment.getApplication())
    private val taken = mutableListOf<List<SourceEvent>>()
    private val breaks = (1L..3L).map { SourceEvent.BeamBreak(it, 1_761_725_400_000 + it) }

    private fun TestScope.controller(source: CutoffSource?) =
        SourceController(store, { testScheduler.currentTime }, storeScope = backgroundScope) { batch, _ ->
            taken +=
                batch
        }.apply { sourceFor = { source } }

    private fun choose(
        kind: SourceKind,
        beam: DeviceId = DeviceId.TEST_BEAM,
        chips: DeviceId = DeviceId.TEST_CHIPS,
    ) = store.save(store.load().copy(cutoffSource = kind, beamDevice = beam, chipsDevice = chips))

    private val endless =
        CutoffSource {
            flow {
                breaks.forEach { emit(it) }
                awaitCancellation()
            }
        }

    @Test
    fun byHandNothingRuns() =
        runTest {
            choose(SourceKind.HAND)
            val controller = controller(endless)
            controller.start(backgroundScope)
            runCurrent()
            assertNull(controller.device.value)
            assertEquals(SourceState.Stopped, controller.status.value.state)
            assertEquals(emptyList<List<SourceEvent>>(), taken)
        }

    @Test
    fun aStubDeviceIsNotAvailableAndNeverStarted() =
        runTest {
            choose(SourceKind.CHIPS, chips = DeviceId.LLRP)
            val controller = controller(endless)
            controller.start(backgroundScope)
            runCurrent()
            assertEquals(DeviceId.LLRP, controller.device.value)
            assertEquals(SourceState.NotAvailable, controller.status.value.state)
            assertEquals(emptyList<List<SourceEvent>>(), taken)
        }

    @Test
    fun theChosenSourceRunsAndItsEventsGoOnInOrderUntilStopped() =
        runTest {
            choose(SourceKind.BEAM)
            val controller = controller(endless)
            controller.start(backgroundScope)
            runCurrent()
            assertEquals(SourceState.Running, controller.status.value.state)
            assertEquals(breaks, taken.flatten())
            controller.stop()
            runCurrent()
            assertEquals(SourceState.Stopped, controller.status.value.state)
            assertNull(controller.device.value)
        }

    @Test
    fun aBatchThatCannotBeStoredIsTriedAgainAndSaysWhy() =
        runTest {
            choose(SourceKind.BEAM)
            var failures = 2
            val controller =
                SourceController(store, { testScheduler.currentTime }, storeScope = backgroundScope) { batch, _ ->
                    check(failures-- <= 0) { "database or disk is full" }
                    taken += batch
                }.apply { sourceFor = { endless } }
            controller.start(backgroundScope)
            runCurrent()
            assertEquals(SourceError.NotStored("database or disk is full"), controller.status.value.lastError)
            assertEquals(SourceState.Running, controller.status.value.state)
            advanceTimeBy(1_000L + 2_000 + 1)
            runCurrent()
            assertEquals(breaks, taken.flatten())
            assertNull(controller.status.value.lastError)
        }

    /** Breaks 1, then 2 and 3 a moment later, then [more] when the source starts again. */
    private fun twoRuns(vararg more: SourceEvent): CutoffSource {
        var runs = 0
        return CutoffSource {
            flow {
                if (runs++ == 0) {
                    emit(breaks[0])
                    delay(1)
                    emit(breaks[1])
                    emit(breaks[2])
                } else {
                    more.forEach { emit(it) }
                }
                awaitCancellation()
            }
        }
    }

    private fun TestScope.slowController(
        source: CutoffSource,
        release: CompletableDeferred<Unit>,
        competition: () -> Long = { 0L },
    ) = SourceController(
        store,
        { testScheduler.currentTime },
        competition = competition,
        storeScope = backgroundScope,
    ) {
        batch,
        _,
        ->
        release.await()
        taken += batch
    }.apply { sourceFor = { source } }

    @Test
    fun eventsTheSourceSentBeforeAStopAreStoredAfterIt() =
        runTest {
            choose(SourceKind.BEAM)
            val release = CompletableDeferred<Unit>()
            val controller = slowController(twoRuns(), release)
            controller.start(backgroundScope)
            runCurrent()
            advanceTimeBy(2)
            runCurrent()
            controller.stop()
            release.complete(Unit)
            runCurrent()
            assertEquals(listOf(listOf(breaks[0]), breaks.drop(1)), taken)
        }

    @Test
    fun eventsTakenBeforeANewCompetitionAreDroppedNotStoredInIt() =
        runTest {
            choose(SourceKind.BEAM)
            val release = CompletableDeferred<Unit>()
            val later = SourceEvent.BeamBreak(9, 1_761_725_400_009)
            val source =
                CutoffSource {
                    flow {
                        emit(breaks[0])
                        delay(1)
                        emit(breaks[1])
                        emit(breaks[2])
                        delay(10)
                        emit(later)
                        awaitCancellation()
                    }
                }
            var competition = 0L
            val controller = slowController(source, release) { competition }
            controller.start(backgroundScope)
            runCurrent()
            advanceTimeBy(2)
            runCurrent()
            // Break 1 is in the sink already (which checks again under its lock); 2 and 3 wait.
            competition++
            release.complete(Unit)
            advanceTimeBy(20)
            runCurrent()
            assertEquals(listOf(breaks[0], later), taken.flatten())
        }

    @Test
    fun aRestartStoresTheEarlierRunsEventsFirst() =
        runTest {
            choose(SourceKind.BEAM)
            val release = CompletableDeferred<Unit>()
            val later = SourceEvent.BeamBreak(9, 1_761_725_400_009)
            val controller = slowController(twoRuns(later), release)
            controller.start(backgroundScope)
            runCurrent()
            advanceTimeBy(2)
            runCurrent()
            choose(SourceKind.CHIPS)
            controller.restart()
            runCurrent()
            release.complete(Unit)
            runCurrent()
            assertEquals(breaks + later, taken.flatten())
        }

    @Test
    fun anEventTheDeviceDidNotTimeIsTimedAsItArrives() =
        runTest {
            choose(SourceKind.CHIPS)
            val base = 1_767_261_600_000L
            val controller =
                SourceController(
                    store,
                    { testScheduler.currentTime },
                    wallClock = { base + testScheduler.currentTime },
                    storeScope = backgroundScope,
                ) { batch, _ ->
                    delay(5_000) // a slow store: the time must not be taken now
                    taken += batch
                }.apply {
                    sourceFor = {
                        CutoffSource {
                            flow {
                                delay(1_000)
                                emit(SourceEvent.ChipRead("A1"))
                                emit(SourceEvent.ChipRead("B1", timeMs = base))
                                awaitCancellation()
                            }
                        }
                    }
                }
            controller.start(backgroundScope)
            advanceTimeBy(7_000)
            runCurrent()
            assertEquals(listOf(base + 1_000, base), taken.flatten().map { (it as SourceEvent.ChipRead).timeMs })
        }

    @Test
    fun aBurstOf10000ReadsIsHandedOnInOrderOffTheMainThread() {
        choose(SourceKind.CHIPS)
        val reads = List(10_000) { SourceEvent.ChipRead("C${it % 50}", 1_767_261_600_000 + it) }
        val stored = Collections.synchronizedList(mutableListOf<SourceEvent>())
        val threads = Collections.synchronizedSet(mutableSetOf<Thread>())
        val controller =
            SourceController(store, System::currentTimeMillis) { batch, _ ->
                threads += Thread.currentThread()
                stored += batch
            }.apply {
                sourceFor = {
                    CutoffSource {
                        flow {
                            reads.forEach { emit(it) }
                            awaitCancellation()
                        }
                    }
                }
            }
        // As the timing-mode service runs it.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            controller.start(scope)
            val deadline = System.currentTimeMillis() + 20_000
            while (stored.size < reads.size && System.currentTimeMillis() < deadline) {
                Thread.sleep(10)
            }
            assertEquals(reads, stored.toList())
            assertFalse(Looper.getMainLooper().thread in threads)
        } finally {
            controller.stop()
            scope.cancel()
        }
    }

    @Test
    fun aStoreStillFailingFromTheRunBeforeARestartShowsOnTheRunAfterIt() =
        runTest {
            choose(SourceKind.BEAM)
            val controller =
                SourceController(store, { testScheduler.currentTime }, storeScope = backgroundScope) { _, _ ->
                    error("database or disk is full")
                }.apply { sourceFor = { endless } }
            controller.start(backgroundScope)
            runCurrent()
            choose(SourceKind.CHIPS)
            controller.restart()
            runCurrent()
            assertEquals(DeviceId.TEST_CHIPS, controller.device.value)
            assertEquals(SourceError.NotStored("database or disk is full"), controller.status.value.lastError)
        }

    @Test
    fun aBatchFailingForMinutesIsDroppedSoTheOnesBehindItAreStored() =
        runTest {
            choose(SourceKind.BEAM)
            val source =
                CutoffSource {
                    flow {
                        emit(breaks[0])
                        delay(1)
                        emit(breaks[1])
                        awaitCancellation()
                    }
                }
            val controller =
                SourceController(store, { testScheduler.currentTime }, storeScope = backgroundScope) { batch, _ ->
                    check(breaks[0] !in batch) { "constraint failed" }
                    taken += batch
                }.apply { sourceFor = { source } }
            controller.start(backgroundScope)
            // Ten tries with pauses of 1, 2, 4, 8, 16 and four of 30 s between them: 151 s.
            advanceTimeBy(151_000L - 10)
            runCurrent()
            assertEquals(SourceError.NotStored("constraint failed"), controller.status.value.lastError)
            advanceTimeBy(20)
            runCurrent()
            assertEquals(listOf(breaks[1]), taken.flatten())
            assertEquals(SourceError.Dropped(1, "constraint failed"), controller.status.value.lastError)
        }

    @Test
    fun aStoreErrorGoesWithTheCompetitionItFailedFor() =
        runTest {
            choose(SourceKind.BEAM)
            var competition = 0L
            val controller =
                SourceController(
                    store,
                    { testScheduler.currentTime },
                    competition = { competition },
                    storeScope = backgroundScope,
                ) { _, _ -> error("database or disk is full") }.apply { sourceFor = { endless } }
            controller.start(backgroundScope)
            runCurrent()
            assertEquals(SourceError.NotStored("database or disk is full"), controller.status.value.lastError)
            competition++
            advanceTimeBy(5_000)
            runCurrent()
            assertNull(controller.status.value.lastError)
        }

    @Test
    fun aRetryRepeatsOnlyTheKindOfEventsThatFailed() =
        runTest {
            choose(SourceKind.BEAM)
            val read = SourceEvent.ChipRead("A1", 1_761_725_400_100)
            var readFailures = 1
            val controller =
                SourceController(store, { testScheduler.currentTime }, storeScope = backgroundScope) { batch, _ ->
                    if (batch.any { it is SourceEvent.ChipRead }) {
                        check(readFailures-- <= 0) { "database is locked" }
                    }
                    taken += batch
                }.apply {
                    sourceFor = {
                        CutoffSource {
                            flow {
                                emit(breaks[0])
                                emit(read)
                                awaitCancellation()
                            }
                        }
                    }
                }
            controller.start(backgroundScope)
            advanceTimeBy(2_000)
            runCurrent()
            assertEquals(listOf(listOf<SourceEvent>(breaks[0]), listOf<SourceEvent>(read)), taken)
        }

    @Test
    fun aRestartRunsTheSourceTheSettingsNowChoose() =
        runTest {
            choose(SourceKind.BEAM)
            val controller = controller(endless)
            controller.start(backgroundScope)
            runCurrent()
            choose(SourceKind.CHIPS, chips = DeviceId.R2000)
            controller.restart()
            runCurrent()
            assertEquals(DeviceId.R2000, controller.device.value)
            assertEquals(SourceState.NotAvailable, controller.status.value.state)
        }

    @Test
    fun aSaveThatKeepsTheDeviceLeavesTheRunningSourceAlone() =
        runTest {
            choose(SourceKind.BEAM)
            var runs = 0
            val controller =
                controller(
                    CutoffSource {
                        flow {
                            runs++
                            awaitCancellation()
                        }
                    },
                )
            controller.start(backgroundScope)
            runCurrent()
            store.save(store.load().copy(deadTimeS = 9, finishMode = true))
            controller.restart()
            runCurrent()
            assertEquals(1, runs)
            choose(SourceKind.CHIPS)
            controller.restart()
            runCurrent()
            assertEquals(2, runs)
        }

    @Test
    fun aRestartWhileStoppedStartsNothing() =
        runTest {
            choose(SourceKind.BEAM)
            val controller = controller(endless)
            controller.restart()
            runCurrent()
            assertNull(controller.device.value)
            assertEquals(emptyList<List<SourceEvent>>(), taken)
        }
}
