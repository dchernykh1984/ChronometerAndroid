package com.dchernykh.chronometer.time

import android.Manifest
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

private const val WORLD_MS = 1_800_000_000_000.0

class GnssCheckTest {
    @Test
    fun aFixIsTakenWhenSteadyAndNearTheInternetsOrElseTheSystemsTime() {
        val check = GnssCheck()
        assertEquals(GnssVerdict.WAITING, check.verdict(WORLD_MS, null, WORLD_MS))
        assertEquals(GnssVerdict.TAKEN, check.verdict(WORLD_MS + 4, null, WORLD_MS + 30_000))
        assertEquals(GnssVerdict.UNSTEADY, check.verdict(WORLD_MS + 30, null, WORLD_MS)) // jammed
        assertEquals(GnssVerdict.FAR_FROM_INTERNET, check.verdict(WORLD_MS + 31, WORLD_MS - 1_500, WORLD_MS))
        assertEquals(GnssVerdict.TAKEN, check.verdict(WORLD_MS + 31, WORLD_MS + 600, WORLD_MS + 3_000_000))
        // A spoofed hour, no internet to settle it: the phone's own clock does.
        assertEquals(GnssVerdict.WAITING, GnssCheck().verdict(WORLD_MS, null, WORLD_MS))
        val spoofed = GnssCheck()
        spoofed.verdict(WORLD_MS + 3_600_000, null, WORLD_MS)
        assertEquals(GnssVerdict.FAR_FROM_SYSTEM, spoofed.verdict(WORLD_MS + 3_600_000, null, WORLD_MS))
        spoofed.forget()
        assertEquals(GnssVerdict.WAITING, spoofed.verdict(WORLD_MS, null, WORLD_MS))
    }

    @Test
    fun aFixsTimeIsWorldLessMonotonicAndHowFarItMayBeOff() {
        assertEquals(WORLD_MS - 2_000 to 1.5, fixOffset(WORLD_MS.toLong(), 2_000_000_000L, 1e6))
        assertEquals(UNKNOWN_PAIRING_MS + 0.5, fixOffset(WORLD_MS.toLong(), 0L, null).second, 1e-9)
    }
}

/** A keeper on a monotonic clock and a system one, moved by hand. */
private class Hand {
    var monoMs = 100_000.0
    var systemAheadMs = 0.0

    fun system(): Long = (WORLD_MS + monoMs + systemAheadMs).toLong()

    fun keeper(): ClockKeeper = ClockKeeper(DeviceClock({ monoMs }, ::system), { monoMs }, ::system)
}

class ClockKeeperTest {
    @Test
    fun theBestSourceAnsweringWinsAndALostOneGivesWayAsItGoesStale() {
        val hand = Hand()
        val keeper = hand.keeper()
        assertEquals(ClockReading(ClockSource.SYSTEM, null), keeper.reading())
        assertTrue(keeper.offer(WORLD_MS + 20, 8.0, ClockSource.INTERNET))
        keeper.offerGnss(WORLD_MS, 1.0)
        keeper.offerGnss(WORLD_MS, 1.0) // the second fix: steady, and better than NTP's
        assertEquals(ClockSource.GNSS, keeper.reading().source)
        assertFalse(keeper.offer(WORLD_MS + 20, 8.0, ClockSource.INTERNET)) // worse: not taken
        hand.monoMs += 200_000 // GNSS gone indoors: 1 ms + 50 ppm of 200 s is 11 ms
        assertTrue(keeper.offer(WORLD_MS + 2, 8.0, ClockSource.INTERNET))
        assertEquals(ClockSource.INTERNET, keeper.reading().source)
        assertEquals(8.0, keeper.reading().withinMs!!, 1e-9)
    }

    @Test
    fun aGnssTimeDroppedIsSaidOnceAndAnotherDroppedAgain() {
        val hand = Hand()
        val keeper = hand.keeper()
        keeper.offer(WORLD_MS, 8.0, ClockSource.INTERNET)
        keeper.offerGnss(WORLD_MS + 1_500, 1.0)
        keeper.offerGnss(WORLD_MS + 1_500, 1.0) // spoofed 1.5 s ahead: not taken
        assertEquals(ClockNews.GnssDropped(GnssVerdict.FAR_FROM_INTERNET), keeper.news.value)
        assertEquals(ClockSource.INTERNET, keeper.reading().source)
        keeper.offerGnss(WORLD_MS + 1_600, 1.0)
        assertEquals(ClockNews.GnssDropped(GnssVerdict.UNSTEADY), keeper.news.value)
        keeper.stopGnss()
        keeper.offerGnss(WORLD_MS, 1.0) // started anew: waiting, nothing said
        assertEquals(ClockNews.GnssDropped(GnssVerdict.UNSTEADY), keeper.news.value)
        keeper.offerGnss(WORLD_MS, 1.0) // steady and near the internet's: taken
        assertNull(keeper.news.value) // no more said dropped
        assertEquals(ClockSource.GNSS, keeper.reading().source)
    }

    @Test
    fun aCorrectionOfOverASecondIsNews() {
        val hand = Hand()
        hand.systemAheadMs = 5_000.0 // the phone's clock set 5 s ahead by hand
        val keeper = hand.keeper()
        keeper.offer(WORLD_MS + 5_000, 8.0, ClockSource.INTERNET)
        keeper.offer(WORLD_MS, 8.0, ClockSource.INTERNET)
        assertEquals(ClockNews.Corrected(-5_000, ClockSource.INTERNET), keeper.news.value)
    }

    @Test
    fun ntpIsMeasuredOnAThreadOfItsOwnAndASecondStartChangesNothing() {
        val hand = Hand()
        val keeper = hand.keeper()
        var rounds = 0
        keeper.measure = {
            rounds++
            WORLD_MS + 3 to 6.0
        }
        keeper.keepMeasuring()
        keeper.keepMeasuring()
        val deadline = System.currentTimeMillis() + 5_000
        while (keeper.reading().withinMs == null && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(ClockReading(ClockSource.INTERNET, 6.0), keeper.reading())
        assertEquals(1, rounds) // the next is 64 s away
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GnssTimeTest {
    private val app = RuntimeEnvironment.getApplication()

    private fun fix(
        timeMs: Long,
        elapsedNanos: Long,
        provider: String = LocationManager.GPS_PROVIDER,
    ): Location =
        Location(provider).apply {
            time = timeMs
            elapsedRealtimeNanos = elapsedNanos
            elapsedRealtimeUncertaintyNanos = 400_000.0
        }

    @Test
    fun withoutTheLocationsLeaveGnssIsNotUsedAndThatIsSaid() {
        val keeper = ClockKeeper()
        keeper.startGnss(app)
        assertEquals(ClockNews.NoLocation, keeper.news.value)
    }

    @Test
    fun gnssFixesSteadyAndPlausibleSetTheClock() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val manager = app.getSystemService(LocationManager::class.java)
        shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        val keeper = ClockKeeper()
        keeper.startGnss(app)
        assertNull(keeper.news.value)
        val now = SystemClock.elapsedRealtimeNanos()
        val world = System.currentTimeMillis() + 250 // the phone's clock 250 ms behind
        shadowOf(manager).simulateLocation(fix(world, now))
        shadowOf(manager).simulateLocation(fix(world + 1_000, now + 1_000_000_000L))
        shadowOf(manager).simulateLocation(fix(world + 2_000, now + 2_000_000_000L, LocationManager.NETWORK_PROVIDER))
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(ClockSource.GNSS, keeper.reading().source)
        assertEquals(0.9, keeper.reading().withinMs!!, 0.05)
        keeper.stopGnss()
    }
}
