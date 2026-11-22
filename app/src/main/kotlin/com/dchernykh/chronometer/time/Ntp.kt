package com.dchernykh.chronometer.time

import java.io.Closeable
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.security.SecureRandom

/*
 * The phone's clock against world time by NTP (ChronometerPython's spec 003, FR-011), as the
 * desktop asks it (its app/device_time.py): several servers, a few times each, each one's
 * shortest round trip kept; a server not synchronised, or one that disagrees with the others
 * by more than its round trip allows, is not taken.
 */

val NTP_SERVERS = listOf("time.google.com", "time.cloudflare.com", "pool.ntp.org", "time.apple.com", "time.windows.com")

/** Asked of each server in a round: its shortest round trip is kept. */
const val NTP_QUERIES = 4

/** An answer later than this is none. */
const val NTP_WAIT_MS = 1_000

/** A round. */
const val NTP_EVERY_MS = 64_000L

private const val AGREE_MS = 1.0 // servers agree within this, over what their trips allow
private const val NTP_PORT = 123
private const val PACKET = 48
private const val LARGEST_ANSWER = 512
private const val NTP_EPOCH_S = 2_208_988_800L // from 1900 to 1970
private const val ERA_S = 4_294_967_296.0 // NTP's seconds wrap in 2036
private const val FIRST_BYTE = 0x23 // LI 0, version 4, mode 3: a client
private const val SERVER_MODE = 4
private const val ALARM = 3
private val SYNCHRONISED = 1..15
private val cookies = SecureRandom()

/** One exchange: world time less the phone's monotonic clock, and the round trip less the server's own time. */
data class NtpAnswer(
    val server: String,
    val offsetMs: Double,
    val tripMs: Double,
)

/** An SNTP version 4 client request, its transmit time a cookie the answer echoes: no clock of the phone is sent. */
fun ntpRequest(cookie: Long): ByteArray =
    ByteBuffer
        .allocate(PACKET)
        .put(0, FIRST_BYTE.toByte())
        .putLong(PACKET - Long.SIZE_BYTES, cookie)
        .array()

/** An NTP timestamp as Unix milliseconds; seconds below 2^31 are past 2036. */
fun ntpMs(stamp: Long): Double {
    var seconds = (stamp ushr Int.SIZE_BITS).toDouble()
    if (seconds < ERA_S / 2) seconds += ERA_S
    return (seconds - NTP_EPOCH_S) * 1_000.0 + (stamp and 0xFFFFFFFFL) * 1_000.0 / ERA_S
}

/**
 * A server's reply to the request of [cookie], sent and got at [sentMs] and [gotMs] on the
 * phone's clock; null for one not to be taken: not a server's, not this request's, or not
 * synchronised (stratum 0 or 16 and over, or the alarm).
 */
fun ntpAnswer(
    server: String,
    data: ByteArray,
    cookie: Long,
    sentMs: Double,
    gotMs: Double,
): NtpAnswer? {
    if (data.size < PACKET) return null
    val first = data[0].toInt() and 0xFF
    val stratum = data[1].toInt() and 0xFF
    val words = ByteBuffer.wrap(data)
    val synchronised = (first and 7) == SERVER_MODE && (first ushr 6) != ALARM && stratum in SYNCHRONISED
    if (!synchronised || words.getLong(24) != cookie) return null
    val received = ntpMs(words.getLong(32))
    val sent = ntpMs(words.getLong(40))
    val trip = (gotMs - sentMs) - (sent - received)
    return if (trip < 0) null else NtpAnswer(server, ((received - sentMs) + (sent - gotMs)) / 2, trip)
}

/** One server's UDP: a request out, its answer in, or a [SocketTimeoutException] once [NTP_WAIT_MS] went by. */
interface NtpLink : Closeable {
    @Throws(IOException::class)
    fun send(request: ByteArray)

    @Throws(IOException::class)
    fun receive(): ByteArray
}

private class UdpLink(
    private val address: InetAddress,
) : NtpLink {
    private val socket = DatagramSocket().apply { soTimeout = NTP_WAIT_MS }

    override fun send(request: ByteArray) = socket.send(DatagramPacket(request, request.size, address, NTP_PORT))

    override fun receive(): ByteArray {
        val packet = DatagramPacket(ByteArray(LARGEST_ANSWER), LARGEST_ANSWER)
        socket.receive(packet)
        return packet.data.copyOf(packet.length)
    }

    override fun close() = socket.close()
}

/** A link to [server]; an [IOException] when its name is not found or no socket opens. */
fun udpLink(server: String): NtpLink = UdpLink(InetAddress.getByName(server))

/**
 * The answers [server] gives to [tries] requests, on [clockMs]; none when it is not found. A
 * refused try is tried again; one unanswered ends the server's turn, so five silent servers
 * take 5 s, not 20.
 */
fun askNtp(
    server: String,
    clockMs: () -> Double,
    link: (String) -> NtpLink = ::udpLink,
    tries: Int = NTP_QUERIES,
): List<NtpAnswer> {
    val answers = mutableListOf<NtpAnswer>()
    try {
        link(server).use { udp ->
            repeat(tries) {
                val cookie = cookies.nextLong()
                val sent = clockMs()
                val data =
                    try {
                        udp.send(ntpRequest(cookie))
                        udp.receive()
                    } catch (_: SocketTimeoutException) {
                        return answers // silent once: not asked again this round
                    } catch (_: IOException) {
                        return@repeat // refused, say: the next try
                    }
                ntpAnswer(server, data, cookie, sent, clockMs())?.let(answers::add)
            }
        }
    } catch (_: IOException) {
        // Not found, or no socket at all: no network.
    }
    return answers
}

/**
 * The offset and how far it may be off, from each server's best answer: one further from the
 * others' median than its half round trip allows disagrees, and is dropped. Null with none.
 */
fun combineNtp(best: List<NtpAnswer>): Pair<Double, Double>? {
    if (best.isEmpty()) return null
    val middle = median(best.map(NtpAnswer::offsetMs))
    val kept = best.filter { kotlin.math.abs(it.offsetMs - middle) <= it.tripMs / 2 + AGREE_MS }.ifEmpty { best }
    return median(kept.map(NtpAnswer::offsetMs)) to kept.minOf(NtpAnswer::tripMs) / 2
}

/** A round: every server asked, each one's shortest round trip kept, combined. */
fun measureNtp(
    clockMs: () -> Double,
    servers: List<String> = NTP_SERVERS,
    ask: (String, () -> Double) -> List<NtpAnswer> = { server, clock -> askNtp(server, clock) },
): Pair<Double, Double>? =
    combineNtp(servers.mapNotNull { server -> ask(server, clockMs).minByOrNull(NtpAnswer::tripMs) })

internal fun median(values: List<Double>): Double {
    val sorted = values.sorted()
    val half = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[half] else (sorted[half - 1] + sorted[half]) / 2
}
