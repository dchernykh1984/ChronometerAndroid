package com.dchernykh.chronometer.time

import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.roundToLong

/** Where the clock's time comes from (ChronometerPython's spec 003, FR-007). */
enum class ClockSource {
    SYSTEM,
    INTERNET,
    GNSS,
}

/** What the referee is told of the clock besides its line (FR-009, FR-010, FR-014). */
sealed interface ClockNews {
    /** A correction of over a second, taken at once: the cutoffs before it were on the old clock. */
    data class Corrected(
        val byMs: Long,
        val source: ClockSource,
    ) : ClockNews

    /** A GNSS time not taken: jammed or spoofed, say. */
    data class GnssDropped(
        val why: GnssVerdict,
    ) : ClockNews

    /** The location not allowed: GNSS is not used. */
    data object NoLocation : ClockNews

    /** The phone's location switched off: GNSS gives no time until it is on. */
    data object LocationOff : ClockNews
}

private const val DRIFT_PPM = 50.0 // how fast an offset goes stale while its rate is not known
private const val RATE_PPM = 5.0 // and while it is: the rate moves with the temperature
private const val RATE_SPAN_MS = 600_000.0 // measurements this far apart give the rate
private const val RATE_POINTS = 3
private const val KEPT_MS = 7_200_000.0 // measurements kept for the rate
private const val POINT_EVERY_MS = 10_000.0 // GNSS's fix a second kept no closer than this
private const val EASE = 0.1 // a correction that sets the clock back runs it at 0.9 of its speed
private const val AT_ONCE_MS = 1_000.0 // a correction over this is taken at once, and said
private const val PER_MILLION = 1e-6

/**
 * World time as well as the phone knows it: its monotonic clock plus the offset measured, its
 * rate followed from one measurement to the next, so it keeps world time while the internet and
 * GNSS are gone; with no measurement, the system's time as it is. It never runs backwards for a
 * correction under a second, and it never sets the system's clock. As the desktop's
 * (ChronometerPython's app/device_time.py), in milliseconds.
 */
class DeviceClock(
    private val mono: () -> Double = { SystemClock.elapsedRealtimeNanos() / 1e6 },
    private val system: () -> Long = System::currentTimeMillis,
) {
    var source = ClockSource.SYSTEM
        private set
    private var errorMs: Double? = null // at the last measurement; null: not checked
    private var offsetMs: Double? = null // world less monotonic, as applied now
    private var targetMs = 0.0 // as measured, the applied one easing to it
    private var rate = 0.0 // the offset's change per monotonic ms, once known
    private var measuredAt = 0.0
    private val points = ArrayList<Pair<Double, Double>>() // (monotonic, offset)
    private var easedAt = mono()
    private var lastMs = 0.0

    /** A measurement, [offset] world less monotonic, from [from]; a correction over a second is said. */
    @Synchronized
    fun take(
        offset: Double,
        error: Double,
        from: ClockSource,
    ): ClockNews? {
        val at = mono()
        val change = offset - (offsetMs ?: (system() - at))
        val big = abs(change) > AT_ONCE_MS
        if (offsetMs == null || change >= 0 || big) {
            offsetMs = offset
            lastMs = 0.0 // a clock set right by over a second may go back
        }
        if (from != source) {
            points.clear() // another source's rate is its own
            rate = 0.0
        }
        points.removeAll { at - it.first > KEPT_MS }
        if (points.isEmpty() || at - points.last().first >= POINT_EVERY_MS) points += at to offset
        if (points.size >= RATE_POINTS && at - points.first().first >= RATE_SPAN_MS) rate = slope(points)
        targetMs = offset
        errorMs = error
        source = from
        measuredAt = at
        return if (big) ClockNews.Corrected(change.roundToLong(), from) else null
    }

    /** The time now, never earlier than the last given but for a correction of over a second. */
    @Synchronized
    fun nowMs(): Long {
        val at = mono()
        val applied =
            offsetMs ?: run {
                easedAt = at
                return system() // not measured: the system's time, as the system sets it
            }
        val target = targetMs + rate * (at - measuredAt)
        val now = if (target < applied) maxOf(target, applied - EASE * (at - easedAt)) else target
        offsetMs = now
        easedAt = at
        lastMs = maxOf(at + now, lastMs)
        return lastMs.roundToLong()
    }

    /** A time on the system's clock, a source's event's, on this one. */
    fun fromSystem(systemMs: Long): Long = systemMs + (nowMs() - system())

    /** How far the clock may be off now; null while it is not checked. */
    @Synchronized
    fun uncertaintyMs(): Double? {
        val error = errorMs ?: return null
        val drift = if (rate != 0.0) RATE_PPM else DRIFT_PPM
        return error + drift * PER_MILLION * (mono() - measuredAt)
    }
}

/** The least-squares slope of [points]: how fast the offset moves. */
internal fun slope(points: List<Pair<Double, Double>>): Double {
    val meanX = points.sumOf { it.first } / points.size
    val meanY = points.sumOf { it.second } / points.size
    val spread = points.sumOf { (it.first - meanX) * (it.first - meanX) }
    return if (spread == 0.0) 0.0 else points.sumOf { (it.first - meanX) * (it.second - meanY) } / spread
}
