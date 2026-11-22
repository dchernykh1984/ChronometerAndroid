package com.dchernykh.chronometer.time

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.ByteBuffer

private const val WORLD_MS = 1_800_000_000_000.0 // world time in these tests, at the phone's 0

private fun stamp(unixMs: Double): Long {
    val seconds = Math.floorDiv(unixMs.toLong(), 1_000L)
    val fraction = ((unixMs - seconds * 1_000.0) / 1_000.0 * 4_294_967_296.0).toLong()
    return ((seconds + 2_208_988_800L) % 4_294_967_296L shl 32) or fraction
}

private fun reply(
    cookie: Long,
    receivedMs: Double,
    sentMs: Double,
    stratum: Int = 2,
    leap: Int = 0,
    mode: Int = 4,
): ByteArray =
    ByteBuffer
        .allocate(48)
        .put(0, ((leap shl 6) or (4 shl 3) or mode).toByte())
        .put(1, stratum.toByte())
        .putLong(24, cookie)
        .putLong(32, stamp(receivedMs))
        .putLong(40, stamp(sentMs))
        .array()

/** A server's UDP as a test has it answer, in turn: a reply to the request, or a failure. */
private class FakeLink(
    val answers: ArrayDeque<Any>,
) : NtpLink {
    private var cookie = 0L
    var closed = false

    override fun send(request: ByteArray) {
        cookie = ByteBuffer.wrap(request).getLong(40)
    }

    override fun receive(): ByteArray =
        when (val next = answers.removeFirst()) {
            is IOException -> throw next
            else -> {
                @Suppress("UNCHECKED_CAST")
                (next as (Long) -> ByteArray)(cookie)
            }
        }

    override fun close() {
        closed = true
    }
}

class NtpTest {
    @Test
    fun aRequestIsAClientsWithACookieForItsTime() {
        val data = ntpRequest(0x0102030405060708)
        assertEquals(48, data.size)
        assertEquals(0x23, data[0].toInt())
        assertEquals((1..8).map { it.toByte() }, data.copyOfRange(40, 48).toList())
    }

    @Test
    fun anNtpTimeReadsAsUnixMillisecondsPast2036Too() {
        assertEquals(WORLD_MS + 250, ntpMs(stamp(WORLD_MS + 250)), 1e-3)
        assertEquals(2_200_000_000_000.0, ntpMs(stamp(2_200_000_000_000.0)), 1e-3)
    }

    @Test
    fun anAnswerGivesTheOffsetAndTheTripLessTheServersTime() {
        // Sent at 1000 on the phone's clock, got at 1030; the server got it at world 2010 and
        // answered at 2012: 28 ms of trip, the offset 1000 + (10 - 18) / 2.
        val one = ntpAnswer("s", reply(7, WORLD_MS + 2_010, WORLD_MS + 2_012), 7, 1_000.0, 1_030.0)!!
        assertEquals(28.0, one.tripMs, 1e-3)
        assertEquals(WORLD_MS + 996.0, one.offsetMs, 1e-3)
    }

    @Test
    fun anAnswerNotToBeTakenIsNone() {
        val bad =
            listOf(
                reply(7, WORLD_MS, WORLD_MS).copyOf(47), // cut short
                reply(7, WORLD_MS, WORLD_MS, mode = 3), // a client's, not a server's
                reply(7, WORLD_MS, WORLD_MS, leap = 3), // the alarm: not synchronised
                reply(7, WORLD_MS, WORLD_MS, stratum = 0), // a kiss of death
                reply(7, WORLD_MS, WORLD_MS, stratum = 16), // not synchronised
                reply(8, WORLD_MS, WORLD_MS), // another request's
                reply(7, WORLD_MS, WORLD_MS + 500), // the server's time longer than the trip
            )
        bad.forEach { assertNull(ntpAnswer("s", it, 7, 0.0, 30.0)) }
    }

    @Test
    fun aServerIsAskedAFewTimesAndNotAgainOnceSilent() {
        val ok: (Long) -> ByteArray = { reply(it, WORLD_MS + 5, WORLD_MS + 5) }
        val answers = ArrayDeque<Any>(listOf(ok, ConnectException("refused"), ok, SocketTimeoutException(), ok))
        val link = FakeLink(answers)
        var tick = 0.0
        val got =
            askNtp("s", { (tick).also { tick += 10.0 } }, { listOf({ link }) }, tries = 5)
        assertEquals(listOf(10, 10), got.map { it.tripMs.toInt() })
        assertEquals(1, answers.size) // nothing asked after the silence
        assertTrue(link.closed)
    }

    @Test
    fun aServersAddressesAreTriedUntilOneAnswers() {
        // Its IPv6 address first, on a network whose IPv6 goes nowhere: its IPv4 one answers.
        val ok: (Long) -> ByteArray = { reply(it, WORLD_MS + 5, WORLD_MS + 5) }
        val v6 = FakeLink(ArrayDeque(listOf(SocketTimeoutException())))
        val v4 = FakeLink(ArrayDeque(listOf(ok, ok)))
        var tick = 0.0
        val got = askNtp("s", { (tick).also { tick += 10.0 } }, { listOf({ v6 }, { v4 }) }, tries = 2)
        assertEquals(listOf(10, 10), got.map { it.tripMs.toInt() })
        assertTrue(v6.closed && v4.closed)
    }

    @Test
    fun aServerNotFoundAnswersNothing() {
        assertEquals(emptyList<NtpAnswer>(), askNtp("s", { 0.0 }, { throw UnknownHostException("s") }))
    }

    @Test
    fun theOffsetIsTheServersMedianOneThatDisagreesDropped() {
        val best =
            listOf(
                NtpAnswer("a", 100.0, 20.0),
                NtpAnswer("b", 103.0, 10.0),
                NtpAnswer("c", 101.0, 30.0),
                NtpAnswer("d", 400.0, 8.0), // 300 ms off with a trip of 8: not to be trusted
            )
        assertEquals(101.0 to 5.0, combineNtp(best))
        assertNull(combineNtp(emptyList()))
    }

    @Test
    fun aRoundKeepsEachServersShortestTrip() {
        val answers =
            mapOf(
                "a" to listOf(NtpAnswer("a", 110.0, 40.0), NtpAnswer("a", 100.0, 10.0)),
                "b" to emptyList(),
                "c" to listOf(NtpAnswer("c", 102.0, 12.0)),
            )
        assertEquals(101.0 to 5.0, measureNtp({ 0.0 }, listOf("a", "b", "c")) { server, _ -> answers.getValue(server) })
    }
}
