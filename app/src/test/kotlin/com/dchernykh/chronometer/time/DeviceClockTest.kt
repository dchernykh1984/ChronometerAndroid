package com.dchernykh.chronometer.time

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val WORLD_MS = 1_800_000_000_000.0 // world time at the phone's monotonic 0

/** A monotonic clock and a system one, moved by hand. */
private class Clocks(
    val systemAheadMs: Double = 0.0,
) {
    var monoMs = 100_000.0

    fun system(): Long = (WORLD_MS + monoMs + systemAheadMs).toLong()

    fun device(): DeviceClock = DeviceClock({ monoMs }, ::system)
}

class DeviceClockTest {
    @Test
    fun unmeasuredTheClockIsTheSystemsAndNotChecked() {
        val clocks = Clocks(systemAheadMs = 37.0)
        val device = clocks.device()
        assertEquals(clocks.system(), device.nowMs())
        assertNull(device.uncertaintyMs())
        assertEquals(ClockSource.SYSTEM, device.source)
    }

    @Test
    fun theFirstMeasurementAndALaterOneForwardAreTakenAtOnce() {
        val clocks = Clocks(systemAheadMs = 37.0)
        val device = clocks.device()
        assertNull(device.take(WORLD_MS, 4.0, ClockSource.INTERNET))
        assertEquals((WORLD_MS + 100_000).toLong(), device.nowMs()) // 37 ms back: at once
        device.take(WORLD_MS + 3, 4.0, ClockSource.INTERNET)
        assertEquals((WORLD_MS + 100_003).toLong(), device.nowMs())
        assertEquals(ClockSource.INTERNET, device.source)
        assertEquals(4.0, device.uncertaintyMs()!!, 1e-9)
    }

    @Test
    fun aCorrectionBackIsEasedInAndTheClockNeverRunsBackwards() {
        val clocks = Clocks()
        val device = clocks.device()
        device.take(WORLD_MS + 50, 4.0, ClockSource.INTERNET)
        val before = device.nowMs()
        device.take(WORLD_MS, 4.0, ClockSource.INTERNET) // 50 ms back
        val readings =
            List(60) {
                clocks.monoMs += 100 // 6 s, 0.1 s apart
                device.nowMs()
            }
        assertTrue(readings[0] > before)
        assertTrue(readings.zipWithNext().all { (a, b) -> b >= a })
        assertEquals((WORLD_MS + clocks.monoMs).toLong(), readings.last()) // in by now
        assertEquals((WORLD_MS + 50 + 100_100 - 10).toLong(), readings[0])
    }

    @Test
    fun aCorrectionOfOverASecondIsTakenAtOnceAndSaid() {
        val clocks = Clocks()
        val device = clocks.device()
        device.take(WORLD_MS + 5_000, 4.0, ClockSource.INTERNET)
        device.nowMs()
        assertEquals(ClockNews.Corrected(-5_000, ClockSource.GNSS), device.take(WORLD_MS, 1.0, ClockSource.GNSS))
        assertEquals((WORLD_MS + 100_000).toLong(), device.nowMs())
    }

    @Test
    fun theOffsetsRateIsFollowedOnceMeasurementsSpanTenMinutes() {
        val clocks = Clocks()
        val device = clocks.device()
        // 64 s apart, the phone's clock 20 ppm slow against world time.
        repeat(12) {
            device.take(WORLD_MS + 20e-6 * clocks.monoMs, 3.0, ClockSource.INTERNET)
            clocks.monoMs += 64_000
        }
        clocks.monoMs += 3_600_000 // an hour with no internet: still the rate's
        assertEquals((WORLD_MS + clocks.monoMs * (1 + 20e-6)), device.nowMs().toDouble(), 1.0)
        // 3 ms at the last measurement, 5 ppm of the hour and the 64 s since it.
        assertEquals(3.0 + 5e-6 * (3_600_000 + 64_000), device.uncertaintyMs()!!, 1e-6)
    }

    @Test
    fun withoutItsRateTheOffsetGoesStaleAt50Ppm() {
        val clocks = Clocks()
        val device = clocks.device()
        device.take(WORLD_MS, 3.0, ClockSource.INTERNET)
        clocks.monoMs += 100_000
        assertEquals(3.0 + 50e-6 * 100_000, device.uncertaintyMs()!!, 1e-9)
    }

    @Test
    fun anEventOnTheSystemsClockIsMovedOntoThisOne() {
        val clocks = Clocks(systemAheadMs = 37.0)
        val device = clocks.device()
        device.take(WORLD_MS, 4.0, ClockSource.INTERNET)
        val systemMs = clocks.system() - 250 // an event 250 ms ago, on the system's clock
        assertEquals((WORLD_MS + clocks.monoMs - 250).toLong(), device.fromSystem(systemMs))
    }

    @Test
    fun aChangeOfSourceStartsItsRateAnew() {
        val clocks = Clocks()
        val device = clocks.device()
        repeat(12) {
            device.take(WORLD_MS + 20e-6 * clocks.monoMs, 3.0, ClockSource.INTERNET)
            clocks.monoMs += 64_000
        }
        device.take(WORLD_MS + 20e-6 * clocks.monoMs, 1.0, ClockSource.GNSS)
        clocks.monoMs += 100_000
        assertEquals(1.0 + 50e-6 * 100_000, device.uncertaintyMs()!!, 1e-9) // no rate: 50 ppm
    }
}
