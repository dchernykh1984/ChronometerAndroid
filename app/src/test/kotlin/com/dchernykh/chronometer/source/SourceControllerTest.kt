package com.dchernykh.chronometer.source

import com.dchernykh.chronometer.data.SettingsStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SourceControllerTest {
    private val store = SettingsStore(RuntimeEnvironment.getApplication())
    private val taken = mutableListOf<List<SourceEvent>>()
    private val breaks = (1L..3L).map { SourceEvent.BeamBreak(it, 1_761_725_400_000 + it) }

    private fun TestScope.controller(source: CutoffSource?) =
        SourceController(store, { testScheduler.currentTime }, storeScope = backgroundScope) { taken += it }
            .apply { sourceFor = { source } }

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
                SourceController(store, { testScheduler.currentTime }, storeScope = backgroundScope) { batch ->
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
    ) = SourceController(store, { testScheduler.currentTime }, storeScope = backgroundScope) { batch ->
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
            controller.restart()
            runCurrent()
            release.complete(Unit)
            runCurrent()
            assertEquals(breaks + later, taken.flatten())
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
