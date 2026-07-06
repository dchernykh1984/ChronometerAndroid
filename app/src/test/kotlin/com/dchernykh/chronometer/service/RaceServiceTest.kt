package com.dchernykh.chronometer.service

import android.content.Intent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RaceServiceTest {
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
}
