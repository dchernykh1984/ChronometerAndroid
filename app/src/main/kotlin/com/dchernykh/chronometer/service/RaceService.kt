package com.dchernykh.chronometer.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat

/**
 * Foreground service that keeps the app alive for the duration of an active event
 * so cutoff recording is not at the mercy of background/energy-saving limits. It
 * only shows the ongoing notification; every cutoff is still written durably to
 * Room by the normal path, independent of this service.
 */
class RaceService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        RaceNotification.ensureChannel(this)
        val notification = RaceNotification.build(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(RaceNotification.ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(RaceNotification.ID, notification)
        }
        // Restart if the system kills us mid-event; the notification returns.
        return START_STICKY
    }

    companion object {
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, RaceService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RaceService::class.java))
        }
    }
}
