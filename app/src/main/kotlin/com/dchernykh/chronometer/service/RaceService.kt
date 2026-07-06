package com.dchernykh.chronometer.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Foreground service that keeps the app alive while timing mode is on, so cutoff
 * recording is not at the mercy of background/energy-saving limits. It only shows
 * the ongoing notification; every cutoff is still written durably to Room by the
 * normal path, independent of this service.
 *
 * [runningState] is the single source of truth for "timing mode on": it follows
 * the real service lifecycle (set in [onStartCommand]/[onDestroy]), so the UI
 * stays correct across recompositions, navigation, recreate and START_STICKY
 * restarts instead of trusting a local UI flag.
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
        mutableRunningState.value = true
        // Restart if the system kills us mid-event; the notification returns.
        return START_STICKY
    }

    override fun onDestroy() {
        mutableRunningState.value = false
        super.onDestroy()
    }

    companion object {
        private val mutableRunningState = MutableStateFlow(false)

        /** True while the foreground service is actually running (timing mode on). */
        val runningState: StateFlow<Boolean> = mutableRunningState.asStateFlow()

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, RaceService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RaceService::class.java))
        }
    }
}
