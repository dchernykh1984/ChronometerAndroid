package com.dchernykh.chronometer.source

import com.dchernykh.chronometer.data.SettingsStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
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
        SourceController(store, { testScheduler.currentTime }) { taken += it }.apply { sourceFor = { source } }

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
