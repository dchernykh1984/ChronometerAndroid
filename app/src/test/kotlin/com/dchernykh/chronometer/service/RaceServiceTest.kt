package com.dchernykh.chronometer.service

import android.content.Intent
import androidx.work.testing.WorkManagerTestInitHelper
import com.dchernykh.chronometer.ChronometerApp
import com.dchernykh.chronometer.source.CutoffSource
import com.dchernykh.chronometer.source.DeviceId
import com.dchernykh.chronometer.source.SourceKind
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RaceServiceTest {
    @Before
    fun setUp() {
        // The source the service starts stores through the cutoffs, which queue uploads.
        WorkManagerTestInitHelper.initializeTestWorkManager(RuntimeEnvironment.getApplication())
    }

    @Test
    fun startCommandPromotesToForegroundWithNotification() {
        val service = Robolectric.buildService(RaceService::class.java).create().get()
        service.onStartCommand(Intent(), 0, 1)
        // The service went foreground with our ongoing notification.
        assertNotNull(shadowOf(service).lastForegroundNotification)
    }

    @Test
    fun runningStateFollowsTheServiceLifecycle() {
        val controller = Robolectric.buildService(RaceService::class.java).create()
        controller.get().onStartCommand(Intent(), 0, 1)
        assertTrue(RaceService.runningState.value)

        controller.destroy()
        assertFalse(RaceService.runningState.value)
    }

    @Test
    fun aSecondStartCommandLeavesTheRunningSourceAlone() {
        val app = RuntimeEnvironment.getApplication() as ChronometerApp
        app.settingsStore.save(app.settingsStore.load().copy(cutoffSource = SourceKind.BEAM))
        var starts = 0
        val builtIn = app.sourceController.sourceFor
        app.sourceController.sourceFor = {
            CutoffSource {
                flow {
                    starts++
                    awaitCancellation()
                }
            }
        }
        val controller = Robolectric.buildService(RaceService::class.java).create()
        try {
            controller.get().onStartCommand(Intent(), 0, 1)
            controller.get().onStartCommand(Intent(), 0, 2)
            assertEquals(DeviceId.TEST_BEAM, app.sourceController.device.value)
            Thread.sleep(200) // the source starts on a worker thread
            assertEquals(1, starts)
        } finally {
            controller.destroy()
            app.sourceController.sourceFor = builtIn
            app.settingsStore.save(app.settingsStore.load().copy(cutoffSource = SourceKind.HAND))
        }
    }
}
