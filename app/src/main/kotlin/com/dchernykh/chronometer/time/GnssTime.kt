package com.dchernykh.chronometer.time

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import kotlin.math.abs

/** What became of a GNSS time (ChronometerPython's spec 003, FR-010). */
enum class GnssVerdict {
    TAKEN,

    /** The first fix: steady or not, the next one tells. */
    WAITING,

    /** It jumped from the fix before: jammed, or spoofed. */
    UNSTEADY,

    /** Over a second off the internet's time, which answered within the hour: spoofed, say. */
    FAR_FROM_INTERNET,

    /** Over a minute off the phone's own clock, with no internet's time to settle it. */
    FAR_FROM_SYSTEM,
}

/** Two fixes in a row agree within this, as the phone's own clock moves. */
const val GNSS_STEADY_MS = 5.0

/** A GNSS time agrees with the internet's within this. */
const val GNSS_NEAR_INTERNET_MS = 1_000.0

/** With no internet's time, a GNSS time agrees with the phone's own clock within this. */
const val GNSS_NEAR_SYSTEM_MS = 60_000.0

/** The internet's time this old still checks GNSS's. */
const val INTERNET_FRESH_MS = 3_600_000.0

/** A fix's time is in whole milliseconds: half of one may be lost. */
private const val ROUNDING_MS = 0.5

/** How far a fix's pairing with the monotonic clock may be off when Android does not say. */
const val UNKNOWN_PAIRING_MS = 100.0

private const val FIX_EVERY_MS = 1_000L
private const val NANOS_PER_MS = 1e6

/**
 * FR-010: a GNSS time is taken only when it is plausible: steady from one fix to the next, and
 * near the internet's when that answered within the hour, else near the phone's own clock. A
 * jammed or spoofed GNSS at a start is then not taken.
 */
class GnssCheck {
    private var last: Double? = null

    /** The verdict on a fix's [offsetMs], with the internet's offset if fresh and the system's. */
    fun verdict(
        offsetMs: Double,
        internetMs: Double?,
        systemMs: Double,
    ): GnssVerdict {
        val previous = last
        last = offsetMs
        return when {
            previous == null -> GnssVerdict.WAITING
            abs(offsetMs - previous) > GNSS_STEADY_MS -> GnssVerdict.UNSTEADY
            internetMs != null && abs(offsetMs - internetMs) > GNSS_NEAR_INTERNET_MS -> GnssVerdict.FAR_FROM_INTERNET
            internetMs == null && abs(offsetMs - systemMs) > GNSS_NEAR_SYSTEM_MS -> GnssVerdict.FAR_FROM_SYSTEM
            else -> GnssVerdict.TAKEN
        }
    }

    /** GNSS stopped: its next fix starts anew. */
    fun forget() {
        last = null
    }
}

/**
 * A fix's time against the monotonic clock: world less monotonic, and how far it may be off,
 * from the uncertainty of the pairing where Android gives it ([uncertaintyNanos]).
 */
fun fixOffset(
    timeMs: Long,
    elapsedNanos: Long,
    uncertaintyNanos: Double?,
): Pair<Double, Double> {
    val pairing = uncertaintyNanos?.div(NANOS_PER_MS) ?: UNKNOWN_PAIRING_MS
    return (timeMs - elapsedNanos / NANOS_PER_MS) to (pairing + ROUNDING_MS)
}

/**
 * The phone's GNSS, a fix a second while timing mode is on: each fix's time against the
 * monotonic clock to [onFix]. A mock location, an app's and not a satellite's, is no fix.
 */
class GnssTime(
    private val context: Context,
    private val onFix: (offsetMs: Double, errorMs: Double) -> Unit,
    /** The phone's location switched on or off: off, GNSS gives no fix. */
    private val onSwitched: (on: Boolean) -> Unit = {},
) {
    private var fixes: Fixes? = null

    /** Fixes asked for; false without the location's leave or a GNSS receiver. */
    @SuppressLint("MissingPermission") // checked first
    fun start(): Boolean {
        if (fixes != null) return true
        val allowed = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        val manager =
            context
                .getSystemService(LocationManager::class.java)
                ?.takeIf { allowed == PackageManager.PERMISSION_GRANTED }
                ?.takeIf { LocationManager.GPS_PROVIDER in it.allProviders }
                ?: return false
        val heard = Fixes()
        manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, FIX_EVERY_MS, 0f, heard, Looper.getMainLooper())
        fixes = heard
        onSwitched(manager.isProviderEnabled(LocationManager.GPS_PROVIDER))
        return true
    }

    fun stop() {
        fixes?.let { context.getSystemService(LocationManager::class.java)?.removeUpdates(it) }
        fixes = null
    }

    // Every method, not a lambda: before Android 11 the others have no body of their own, and a
    // provider switched off would end the app.
    private inner class Fixes : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (location.provider != LocationManager.GPS_PROVIDER || isMock(location)) return
            val uncertainty =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && location.hasElapsedRealtimeUncertaintyNanos()) {
                    location.elapsedRealtimeUncertaintyNanos
                } else {
                    null
                }
            val (offset, error) = fixOffset(location.time, location.elapsedRealtimeNanos, uncertainty)
            onFix(offset, error)
        }

        @Deprecated("Android 10 and before call it")
        override fun onStatusChanged(
            provider: String?,
            status: Int,
            extras: Bundle?,
        ) = Unit

        override fun onProviderEnabled(provider: String) {
            if (provider == LocationManager.GPS_PROVIDER) onSwitched(true)
        }

        override fun onProviderDisabled(provider: String) {
            if (provider == LocationManager.GPS_PROVIDER) onSwitched(false)
        }
    }
}

@Suppress("DEPRECATION")
private fun isMock(location: Location): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) location.isMock else location.isFromMockProvider
