package com.dchernykh.chronometer.time

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** The clock's line for the referee: where its time comes from, and how far it may be off. */
data class ClockReading(
    val source: ClockSource,
    val withinMs: Double?,
)

/**
 * Keeps the phone's clock to world time from every source it has (ChronometerPython's spec 003,
 * FR-007): NTP every 64 s while the app runs, GNSS while timing mode is on. A measurement of the
 * source the clock follows is always taken; one of another only when it is better than what the
 * clock knows now, so the best source answering wins and a lost one gives way as its time goes
 * stale. Measuring never holds timing up (FR-015): NTP runs on a thread of its own.
 */
class ClockKeeper(
    val clock: DeviceClock = DeviceClock(),
    private val mono: () -> Double = { SystemClock.elapsedRealtimeNanos() / 1e6 },
    private val system: () -> Long = System::currentTimeMillis,
) {
    /** One NTP round: the offset and how far it may be off, or null with no answer. */
    var measure: () -> Pair<Double, Double>? = { measureNtp(mono) }

    private val newsState = MutableStateFlow<ClockNews?>(null)

    /** The last thing the referee is to be told of the clock. */
    val news: StateFlow<ClockNews?> = newsState.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var measuring: Job? = null
    private var internet: Pair<Double, Double>? = null // NTP's last offset, and when, for GNSS's check
    private val check = GnssCheck()
    private var lastVerdict = GnssVerdict.WAITING
    private var gnss: GnssTime? = null

    /** NTP every 64 s from now on, while the app lives; a second call changes nothing. */
    @Synchronized
    fun keepMeasuring() {
        if (measuring != null) return
        measuring =
            scope.launch {
                while (isActive) {
                    measure()?.let { (offset, error) -> offer(offset, error, ClockSource.INTERNET) }
                    delay(NTP_EVERY_MS)
                }
            }
    }

    /** A measurement from [source], taken as the class says; true when taken. */
    @Synchronized
    fun offer(
        offsetMs: Double,
        errorMs: Double,
        source: ClockSource,
    ): Boolean {
        if (source == ClockSource.INTERNET) internet = offsetMs to mono()
        val known = clock.uncertaintyMs()
        if (known != null && source != clock.source && errorMs > known) return false
        clock.take(offsetMs, errorMs, source)?.let { newsState.value = it }
        return true
    }

    /** A GNSS fix's offset: taken when plausible (FR-010), else dropped, and the referee told once. */
    @Synchronized
    fun offerGnss(
        offsetMs: Double,
        errorMs: Double,
    ) {
        val fresh = internet?.takeIf { mono() - it.second <= INTERNET_FRESH_MS }?.first
        val verdict = check.verdict(offsetMs, fresh, system() - mono())
        when (verdict) {
            GnssVerdict.TAKEN -> {
                offer(offsetMs, errorMs, ClockSource.GNSS)
                // Plausible again, a cold start's first fixes settled say: no more said dropped.
                if (newsState.value is ClockNews.GnssDropped) newsState.value = null
            }
            GnssVerdict.WAITING -> Unit
            else -> if (verdict != lastVerdict) newsState.value = ClockNews.GnssDropped(verdict)
        }
        lastVerdict = verdict
    }

    /** GNSS asked for, as timing mode starts; without the location's leave, said (FR-014). */
    @Synchronized
    fun startGnss(context: Context) {
        if (gnss != null) return
        val time = GnssTime(context, ::offerGnss, ::gnssSwitched)
        if (time.start()) {
            gnss = time
            if (newsState.value == ClockNews.NoLocation) newsState.value = null
        } else {
            newsState.value = ClockNews.NoLocation
        }
    }

    /** The phone's location switched on or off: off, GNSS's time is not used, and that is said. */
    @Synchronized
    fun gnssSwitched(on: Boolean) {
        if (!on) {
            newsState.value = ClockNews.LocationOff
        } else if (newsState.value == ClockNews.LocationOff) {
            newsState.value = null
        }
    }

    /** GNSS let go, as timing mode stops: what was said of it is said no more. */
    @Synchronized
    fun stopGnss() {
        gnss?.stop()
        gnss = null
        check.forget()
        lastVerdict = GnssVerdict.WAITING
        val news = newsState.value
        if (news is ClockNews.GnssDropped || news == ClockNews.NoLocation || news == ClockNews.LocationOff) {
            newsState.value = null
        }
    }

    /** The clock's line now. */
    fun reading(): ClockReading {
        val within = clock.uncertaintyMs()
        return ClockReading(if (within == null) ClockSource.SYSTEM else clock.source, within)
    }
}
