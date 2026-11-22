package com.dchernykh.chronometer.source.board

import com.dchernykh.chronometer.source.BoardTrouble
import com.dchernykh.chronometer.source.SourceEvent
import kotlin.math.abs
import kotlin.math.roundToLong

/*
 * The beam boards' lines, as the desktop connector reads them: one contract for both,
 * ChronometerPython's specs/001-event-sources/contracts/beam-board-protocol.md (0.4).
 */

/** The names a board's HELLO gives, and the firmware this app is built for. */
val BOARD_NAMES = setOf("beam-rp2040", "beam-esp32c3")
const val BOARD_FIRMWARE = "0.4"

/** A PING every 2 s on USB, every 1 s over Bluetooth: there more trips, more short ones. */
const val PING_EVERY_MS = 2_000L
const val RADIO_PING_EVERY_MS = 1_000L

/** The board's HELLO asked again: its count shows a break whose line was lost, the last say. */
const val HELLO_EVERY_MS = 5_000L

/** A PING unanswered for 6 s: the board's program has stopped. */
const val SILENT_MS = 6_000L

/** Blocked or flickering for longer than any rider takes: no break can be seen. */
const val DARK_MS = 2_000L

/** A break waits this long for the board's clock, then takes the time its line came. */
const val HELD_MS = 3_000L

/** Over Bluetooth the clock takes [RADIO_READY] answers before it is read. */
const val RADIO_HELD_MS = 10_000L

/** A break asked for again and not come within this is said as never come. */
const val RESENT_WITHIN_MS = 10_000L

private const val LONGEST_TRIP_MS = 20.0
private const val SAMPLES = 9
private const val STEP_MS = 50.0
private const val RADIO_SAMPLES = 16
private const val RADIO_READY = 4
private const val RADIO_STEP_MS = 100.0
private const val HELD_MAX = 64
private const val UNANSWERED_KEPT = 4 * RADIO_SAMPLES

/** A board line as its word and the rest: ("BREAK", ["12", "812345"]). */
fun parseLine(line: String): Pair<String, List<String>> {
    val words = line.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return if (words.isEmpty()) "" to emptyList() else words[0].uppercase() to words.drop(1)
}

/** The [count] whole numbers a BREAK or a PONG carries, else null. */
fun wholeNumbers(
    rest: List<String>,
    count: Int,
): List<Long>? {
    if (!rest.all(::isWholeNumber)) return null
    return rest.mapNotNull(String::toLongOrNull).takeIf { it.size == count && rest.size == count }
}

private fun isWholeNumber(word: String): Boolean = word.isNotEmpty() && word.all { it in '0'..'9' }

/**
 * A PING's way there and its PONG's way back, each the arriving clock less the leaving one:
 * their difference is twice the offset, their sum the round trip with the board's wait left out.
 */
private fun trip(
    sentMs: Double,
    gotMs: Double,
    gotUs: Long,
    sentUs: Long,
): Pair<Double, Double> = (gotUs / 1_000.0 - sentMs) to (gotMs - sentUs / 1_000.0)

/** The board's microseconds onto the phone's milliseconds. */
interface BoardClock {
    val read: Boolean

    fun add(
        sentMs: Double,
        gotMs: Double,
        gotUs: Long,
        sentUs: Long,
    )

    fun toPhoneMs(boardUs: Long): Long?
}

/** Over USB: round trips short enough that half of one each way is near the truth. */
class ClockSync : BoardClock {
    private val offsets = ArrayDeque<Double>()

    override val read: Boolean get() = offsets.isNotEmpty()

    override fun add(
        sentMs: Double,
        gotMs: Double,
        gotUs: Long,
        sentUs: Long,
    ) {
        val (there, back) = trip(sentMs, gotMs, gotUs, sentUs)
        if (there + back > LONGEST_TRIP_MS) return
        val offset = (back - there) / 2
        // Round trips this short agree within half of one: one that does not saw the
        // phone's clock set anew, and the offsets before it no longer hold.
        if (offsets.isNotEmpty() && abs(offset - median()) > STEP_MS) offsets.clear()
        offsets.addLast(offset)
        while (offsets.size > SAMPLES) offsets.removeFirst()
    }

    override fun toPhoneMs(boardUs: Long): Long? =
        if (offsets.isEmpty()) null else (boardUs / 1_000.0 + median()).roundToLong()

    private fun median(): Double {
        val sorted = offsets.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2
    }
}

/**
 * Over Bluetooth: a message waits for the radio's next connection event, and a trip's two
 * ways seldom wait alike. The shortest way there and the shortest way back of the last
 * trips are each near the radio's bare delay, so half their difference is the offset; the
 * board's random wait before a PONG gives the way back its short ones too.
 */
class RadioSync : BoardClock {
    private val trips = ArrayDeque<Pair<Double, Double>>()

    override val read: Boolean get() = trips.size >= RADIO_READY

    val offset: Double get() = (trips.minOf { it.second } - trips.minOf { it.first }) / 2

    override fun add(
        sentMs: Double,
        gotMs: Double,
        gotUs: Long,
        sentUs: Long,
    ) {
        val (there, back) = trip(sentMs, gotMs, gotUs, sentUs)
        if (read && abs((back - there) / 2 - offset) > RADIO_STEP_MS) trips.clear() // clock set anew
        trips.addLast(there to back)
        while (trips.size > RADIO_SAMPLES) trips.removeFirst()
    }

    override fun toPhoneMs(boardUs: Long): Long? = if (!read) null else (boardUs / 1_000.0 + offset).roundToLong()
}

/**
 * One board's breaks by count, kept across its link going and coming back: each taken once,
 * those missed asked for again (contract, steps 3 and 5).
 */
class Counts(
    var last: Long = 0,
) {
    val taken = mutableMapOf<Long, Long>() // seq: board time, since its start
    val wanted = mutableMapOf<Long, Long>() // seq: asked for at, phone ms
    val said = mutableSetOf<Long>() // wanted ones said as never come

    /** The board started again: its counts begin anew. */
    fun restart() {
        last = 0
        taken.clear()
        wanted.clear()
        said.clear()
    }
}

/**
 * One link's catching up with the boards' counts (contract, steps 3 and 5): what a HELLO's
 * count and each BREAK say against the counts taken, and the RESENDs they need ([outbox]).
 */
class Catchup(
    val counts: MutableMap<String, Counts>,
) {
    val outbox = ArrayList<String>()

    /**
     * The count board [boardId]'s HELLO gave; true when it shows the board started again. The
     * first HELLO: breaks before it were not this app's, but those that came on this link
     * waiting for it ([firstHeld]) are.
     */
    fun hello(
        boardId: String,
        seq: Long,
        firstHeld: Long?,
        gotMs: Long,
    ): Boolean {
        val known = counts[boardId]
        when {
            known == null -> counts[boardId] = Counts(minOf(seq + 1, firstHeld ?: (seq + 1)) - 1)
            seq > known.last -> resend(known, known.last + 1, seq, gotMs) // missed, or its line lost
            seq < known.last -> {
                known.restart() // it started again, and counted while no link was up
                if (seq > 0) resend(known, 1, seq, gotMs)
                return true
            }
        }
        return false
    }

    /** Whether a BREAK is one this app has not had; a gap before it is asked for again. */
    fun take(
        boardId: String,
        seq: Long,
        boardUs: Long,
        gotMs: Long,
    ): Boolean {
        // No count from a HELLO (another firmware's): from this one on.
        val counts = this.counts.getOrPut(boardId) { Counts(seq - 1) }
        val had = counts.taken[seq]
        when {
            had == boardUs -> return false // said again: had already
            had != null -> counts.restart() // the same count, another time: it started again
            seq < counts.last && seq !in counts.wanted -> counts.restart() // a lower count, not asked for
        }
        if (seq > counts.last + 1) resend(counts, counts.last + 1, seq - 1, gotMs)
        counts.taken[seq] = boardUs
        counts.wanted.remove(seq)
        counts.last = maxOf(counts.last, seq)
        return true
    }

    /**
     * Breaks asked for again that have not come within [RESENT_WITHIN_MS]: they never came,
     * the link down as they did. Said once; taken still, if late.
     */
    fun overdue(nowMs: Long): List<SourceEvent> =
        counts.values.mapNotNull { counts ->
            val late =
                counts.wanted
                    .filter { (seq, asked) -> nowMs - asked > RESENT_WITHIN_MS && seq !in counts.said }
                    .keys
                    .sorted()
            if (late.isEmpty()) {
                null
            } else {
                counts.said += late
                SourceEvent.Board(BoardTrouble.BreaksMissing(late.first(), late.last()))
            }
        }

    private fun resend(
        counts: Counts,
        first: Long,
        last: Long,
        nowMs: Long,
    ) {
        for (seq in first..last) counts.wanted.putIfAbsent(seq, nowMs)
        outbox += "RESEND $first"
    }
}

/** A break that waits for the board's HELLO and its clock. */
private data class Held(
    val seq: Long,
    val boardUs: Long,
    val text: String,
    val gotMs: Long,
)

/**
 * One board, from the link's opening on: what it says, as events for the source, and the
 * lines it is to be sent beside the PINGs ([outbox]). The first PING number and each board
 * id's counts carry over to the next opening (contract, steps 3 to 5).
 */
class BoardBridge(
    val radio: Boolean = false,
    var nextPing: Long = 1,
    val counts: MutableMap<String, Counts> = mutableMapOf(),
) {
    var sync: BoardClock = if (radio) RadioSync() else ClockSync()
        private set
    val pingEveryMs: Long get() = if (radio) RADIO_PING_EVERY_MS else PING_EVERY_MS
    private val catchup = Catchup(counts)

    /** RESEND lines for the board: breaks it counted while no link was up, or lost on the way. */
    val outbox: MutableList<String> get() = catchup.outbox

    /** What the HELLO said, empty before it: no line counts until then. */
    var board: String = ""
        private set

    private var boardId = ""
    private val pings = LinkedHashMap<Long, Long>() // sent at, by number
    private val held = ArrayList<Held>()
    private var dark: String? = null
    private var darkSince = 0L

    /** The next PING line for the board, its sending time kept. */
    fun ping(sentMs: Long): String {
        val n = nextPing++
        pings[n] = sentMs
        pings.keys.removeAll { it <= n - UNANSWERED_KEPT }
        return "PING $n"
    }

    /** What the board's state says now: a lasting trouble, or null once all is well. */
    fun trouble(nowMs: Long): BoardTrouble? {
        if (pings.values.any { nowMs - it > SILENT_MS }) return BoardTrouble.Silent
        if (dark == null || nowMs - darkSince <= DARK_MS) return null
        return if (dark == "blocked") BoardTrouble.Blocked else BoardTrouble.Unsteady
    }

    fun fromBoard(
        line: String,
        gotMs: Long,
    ): List<SourceEvent> {
        val (word, rest) = parseLine(line)
        val pair = wholeNumbers(rest, 2)
        val pong = wholeNumbers(rest, 3)
        return when {
            word == "HELLO" && rest.firstOrNull() in BOARD_NAMES -> hello(rest, gotMs)
            word == "BREAK" && pair != null -> takeBreak(pair, rest[1], gotMs)
            board.isEmpty() -> emptyList() // left from before the HELLO, or another firmware's
            word == "PONG" && pong != null -> {
                answered(pong, gotMs)
                release(null)
            }
            word == "STATUS" -> {
                status(rest, gotMs)
                emptyList()
            }
            word == "ERROR" -> listOf(SourceEvent.Error("board: ${rest.joinToString(" ")}"))
            else -> emptyList() // FLASH, HOLDOFF: the board's own business
        }
    }

    /**
     * Breaks that waited for the board's HELLO and its clock: timed by the board's clock once
     * it is read. Given up on after the first came, at [nowMs], they take the time their lines
     * came, and that is said; null gives none up. And breaks asked for again that never came.
     */
    fun release(nowMs: Long?): List<SourceEvent> {
        val overdue = if (nowMs == null) emptyList() else catchup.overdue(nowMs)
        if (held.isEmpty() || board.isEmpty()) return overdue
        val read = sync.read
        val wait = if (radio) RADIO_HELD_MS else HELD_MS
        if (!read && (nowMs == null || nowMs - held[0].gotMs <= wait)) return overdue
        val waited = held.toList()
        held.clear()
        val events = overdue + waited.flatMap { beam(it.seq, it.boardUs, it.text, it.gotMs) }
        return if (read) events else events + SourceEvent.Board(BoardTrouble.ClockNotRead(waited.size))
    }

    private fun hello(
        rest: List<String>,
        gotMs: Long,
    ): List<SourceEvent> {
        val first = board.isEmpty() // asked again every few seconds: said once
        board = rest.take(3).joinToString(" ")
        boardId = rest.getOrElse(2) { "" }
        val count = rest.getOrNull(3)?.takeIf(::isWholeNumber)?.toLongOrNull()
        if (count != null && catchup.hello(boardId, count, held.minOfOrNull(Held::seq), gotMs)) {
            // Its clock started again with it: a link that stayed up keeps no trip of the old one.
            sync = if (radio) RadioSync() else ClockSync()
        }
        val runs = rest.getOrElse(1) { "?" }
        return if (runs == BOARD_FIRMWARE || !first) {
            emptyList()
        } else {
            listOf(SourceEvent.Board(BoardTrouble.OtherFirmware(runs, BOARD_FIRMWARE)))
        }
    }

    private fun takeBreak(
        numbers: List<Long>,
        text: String,
        gotMs: Long,
    ): List<SourceEvent> {
        if (board.isNotEmpty() && sync.read) return beam(numbers[0], numbers[1], text, gotMs)
        // Before the HELLO, or the clock read: a rider all the same, so it waits; one from
        // a board that never says HELLO never counts.
        if (held.size < HELD_MAX) held += Held(numbers[0], numbers[1], text, gotMs)
        return emptyList()
    }

    private fun beam(
        seq: Long,
        boardUs: Long,
        text: String,
        gotMs: Long,
    ): List<SourceEvent> {
        if (!catchup.take(boardId, seq, boardUs, gotMs)) return emptyList()
        return listOf(SourceEvent.BeamBreak(seq, sync.toPhoneMs(boardUs) ?: gotMs, text))
    }

    private fun answered(
        pong: List<Long>,
        gotMs: Long,
    ) {
        val sent = pings.remove(pong[0])
        pings.keys.removeAll { it < pong[0] } // a later one answered: the board is there
        if (sent != null) sync.add(sent.toDouble(), gotMs.toDouble(), pong[1], pong[2])
    }

    private fun status(
        rest: List<String>,
        gotMs: Long,
    ) {
        val now = rest.singleOrNull()?.takeIf { it == "blocked" || it == "unsteady" }
        if (now != dark) { // said again when asked: since the first
            dark = now
            darkSince = gotMs
        }
    }
}

/**
 * The board's bytes as lines, each with its moment: only a line with its end counts, as one
 * cut short when the link ended may be a BREAK of half its digits. A line is timed when its
 * first piece came, over USB as over Bluetooth (the beam boards' contract): timed at its end,
 * it would carry the time its last characters took, a connection event apart over the radio.
 */
class LineSplitter(
    private val longest: Int = 256,
) {
    private val pending = StringBuilder()
    private var startedAt = 0L

    fun feed(
        bytes: ByteArray,
        at: Long,
    ): List<Pair<String, Long>> {
        val lines = mutableListOf<Pair<String, Long>>()
        for (byte in bytes) {
            val char = (byte.toInt() and 0xFF).toChar()
            when {
                char == '\n' -> {
                    lines += pending.toString().trim() to startedAt
                    pending.clear()
                }
                pending.length < longest -> {
                    if (pending.isEmpty()) startedAt = at
                    pending.append(char)
                }
            }
        }
        return lines
    }
}
