package com.dchernykh.chronometer.service

import android.app.Notification
import android.app.NotificationManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class RaceNotificationTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun ensureChannelCreatesLowImportanceChannel() {
        RaceNotification.ensureChannel(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = manager.getNotificationChannel(RaceNotification.CHANNEL_ID)
        assertNotNull(channel)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
    }

    @Test
    fun buildProducesOngoingNotificationWithAppTitle() {
        RaceNotification.ensureChannel(context)
        val notification = RaceNotification.build(context)
        assertTrue((notification.flags and Notification.FLAG_ONGOING_EVENT) != 0)
        assertEquals("Chronometer", notification.extras.getString(Notification.EXTRA_TITLE))
    }
}
