package com.dchernykh.chronometer.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.dchernykh.chronometer.MainActivity
import com.dchernykh.chronometer.R

/**
 * The ongoing notification and its channel for [RaceService]. Kept separate from
 * the service so the channel/notification building can be unit-tested directly.
 */
object RaceNotification {
    const val CHANNEL_ID = "race_active"
    const val ID = 1

    /** Create the low-importance channel (idempotent) on API 26+. */
    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel =
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.race_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                )
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    /** Build the persistent "timing is active" notification that taps back into the app. */
    fun build(context: Context): Notification =
        NotificationCompat
            .Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(R.string.race_notification_text))
            .setSmallIcon(R.drawable.ic_stat_timer)
            .setOngoing(true)
            .setContentIntent(openAppIntent(context))
            .build()

    private fun openAppIntent(context: Context): PendingIntent {
        val intent =
            Intent(context, MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
    }
}
