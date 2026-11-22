package com.dchernykh.chronometer.service

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.dchernykh.chronometer.ChronometerApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
    // The cutoff source runs here, off the main thread, for as long as timing mode is on.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var sourceStarted = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        RaceNotification.ensureChannel(this)
        val notification = RaceNotification.build(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(RaceNotification.ID, notification, foregroundTypes(this))
        } else {
            startForeground(RaceNotification.ID, notification)
        }
        mutableRunningState.value = true
        // The clock kept to world time while timing mode is on: GNSS too (spec 003, FR-014). A
        // start command after the location was allowed starts GNSS then.
        val clock = (application as ChronometerApp).clockKeeper
        clock.keepMeasuring()
        clock.startGnss(this)
        if (!sourceStarted) {
            // Once a service: another start command must not restart a running device.
            sourceStarted = true
            (application as ChronometerApp).sourceController.start(scope)
        }
        // Restart if the system kills us mid-event; the notification returns.
        return START_STICKY
    }

    override fun onDestroy() {
        (application as ChronometerApp).sourceController.stop()
        (application as ChronometerApp).clockKeeper.stopGnss()
        scope.cancel()
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

/**
 * The service's types as it goes foreground: the location's too once it is allowed, as Android
 * 14 asks of a service reading it, GNSS's time here; asked without the leave, it would refuse.
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
internal fun foregroundTypes(context: Context): Int {
    val located = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    val location = if (located == PackageManager.PERMISSION_GRANTED) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
    return ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or location
}
