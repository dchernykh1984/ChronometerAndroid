package com.dchernykh.chronometer.source.board

import com.dchernykh.chronometer.source.BoardTrouble
import com.dchernykh.chronometer.source.SourceEvent
import kotlin.math.abs
import kotlin.math.roundToLong

/*
 * The beam board's lines, as the desktop connector reads them: one contract for both,
 * ChronometerPython's specs/001-event-sources/contracts/beam-board-protocol.md.
 */

/** The name the board's HELLO gives, and the firmware this app is built for. */
const val BOARD_NAME = "beam-rp2040"
const val BOARD_FIRMWARE = "0.3"

/** A PING every 2 s; one unanswered for 6 s says the board's program has stopped. */
const val PING_EVERY_MS = 2_000L
const val SILENT_MS = 3 * PING_EVERY_MS

/** Blocked or flickering for longer than any rider takes: no break can be seen. */
const val DARK_MS = 2_000L

/** A break waits this long for the board's clock, then takes the time its line came. */
const val HELD_MS = 3_000L

private const val LONGEST_TRIP_MS = 20.0
private const val SAMPLES = 9
private const val STEP_MS = 50.0
private const val HELD_MAX = 64
private const val UNANSWERED_KEPT = 4 * SAMPLES

/** A board line as its word and the rest: ("BREAK", ["12", "812345"]). */
fun parseLine(line: String): Pair<String, List<String>> {
    val words = line.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return if (words.isEmpty()) "" to emptyList() else words[0].uppercase() to words.drop(1)
}

/** The two whole numbers a BREAK or a PONG carries, else null. */
fun twoNumbers(rest: List<String>): Pair<Long, Long>? {
    val numbers = rest.takeIf { it.size == 2 && it.all(::isWholeNumber) }?.map(String::toLongOrNull)
    val first = numbers?.get(0)
    val second = numbers?.get(1)
    return if (first != null && second != null) first to second else null
}

private fun isWholeNumber(word: String): Boolean = word.isNotEmpty() && word.all { it in '0'..'9' }

/**
 * The board's microseconds onto the phone's milliseconds, from round trips whose middle is
 * taken as the moment the board answered (contract, step 3).
 */
class ClockSync {
    private val offsets = ArrayDeque<Double>()

    val read: Boolean get() = offsets.isNotEmpty()

    fun add(
        sentMs: Double,
        gotMs: Double,
        boardUs: Long,
    ) {
        if (gotMs - sentMs > LONGEST_TRIP_MS) return
        val offset = (sentMs + gotMs) / 2 - boardUs / 1_000.0
        // Round trips this short agree within half of one: one that does not saw the
        // phone's clock set anew, and the offsets before it no longer hold.
        if (offsets.isNotEmpty() && abs(offset - median()) > STEP_MS) offsets.clear()
        offsets.addLast(offset)
        while (offsets.size > SAMPLES) offsets.removeFirst()
    }

    fun toPhoneMs(boardUs: Long): Long? = if (offsets.isEmpty()) null else (boardUs / 1_000.0 + median()).roundToLong()

    private fun median(): Double {
        val sorted = offsets.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2
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
 * One board, from the port's opening on: what it says, as events for the source. The
 * first PING number and the last break of each board id carry over to the next opening
 * (contract, steps 3 and 4).
 */
class BoardBridge(
    var nextPing: Long = 1,
    val lastBreaks: MutableMap<String, Long> = mutableMapOf(),
) {
    val sync = ClockSync()

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
        val numbers = twoNumbers(rest)
        return when {
            word == "HELLO" && rest.firstOrNull() == BOARD_NAME -> hello(rest)
            word == "BREAK" && numbers != null -> takeBreak(numbers, rest[1], gotMs)
            board.isEmpty() -> emptyList() // left from before the HELLO, or another firmware's
            word == "PONG" && numbers != null -> {
                answered(numbers.first, numbers.second, gotMs)
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
     * Breaks that waited for the board's HELLO and its clock: timed by the board's clock
     * once it is read. Given up on [HELD_MS] after the first came, at [nowMs], they take
     * the time their lines came, and that is said; null gives none up.
     */
    fun release(nowMs: Long?): List<SourceEvent> {
        if (held.isEmpty() || board.isEmpty()) return emptyList()
        val read = sync.read
        if (!read && (nowMs == null || nowMs - held[0].gotMs <= HELD_MS)) return emptyList()
        val waited = held.toList()
        held.clear()
        val events = waited.flatMap { beam(it.seq, it.boardUs, it.text, it.gotMs) }
        return if (read) events else events + SourceEvent.Board(BoardTrouble.ClockNotRead(waited.size))
    }

    private fun hello(rest: List<String>): List<SourceEvent> {
        board = rest.joinToString(" ")
        boardId = rest.getOrElse(2) { "" }
        val runs = rest.getOrElse(1) { "?" }
        return if (runs == BOARD_FIRMWARE) {
            emptyList()
        } else {
            listOf(SourceEvent.Board(BoardTrouble.OtherFirmware(runs, BOARD_FIRMWARE)))
        }
    }

    private fun takeBreak(
        numbers: Pair<Long, Long>,
        text: String,
        gotMs: Long,
    ): List<SourceEvent> {
        if (board.isNotEmpty() && sync.read) return beam(numbers.first, numbers.second, text, gotMs)
        // Before the HELLO, or the clock read: a rider all the same, so it waits; one from
        // a board that never says HELLO never counts.
        if (held.size < HELD_MAX) held += Held(numbers.first, numbers.second, text, gotMs)
        return emptyList()
    }

    private fun beam(
        seq: Long,
        boardUs: Long,
        text: String,
        gotMs: Long,
    ): List<SourceEvent> {
        val beam = SourceEvent.BeamBreak(seq, sync.toPhoneMs(boardUs) ?: gotMs, text)
        val last = lastBreaks[boardId] ?: 0L
        lastBreaks[boardId] = seq
        // A count that skips some: they never came, the link down as they did, though not
        // the board's power. A count begun again is the board started again.
        return if (last in 1 until seq - 1) {
            listOf(beam, SourceEvent.Board(BoardTrouble.BreaksMissing(last + 1, seq - 1)))
        } else {
            listOf(beam)
        }
    }

    private fun answered(
        n: Long,
        boardUs: Long,
        gotMs: Long,
    ) {
        val sent = pings.remove(n)
        pings.keys.removeAll { it < n } // a later one answered: the board is there
        if (sent != null) sync.add(sent.toDouble(), gotMs.toDouble(), boardUs)
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
 * The board's bytes as lines: only a line with its end counts, as one cut short when the
 * port ended may be a BREAK of half its digits.
 */
class LineSplitter(
    private val longest: Int = 256,
) {
    private val pending = StringBuilder()

    fun feed(bytes: ByteArray): List<String> {
        val lines = mutableListOf<String>()
        for (byte in bytes) {
            val char = (byte.toInt() and 0xFF).toChar()
            when {
                char == '\n' -> {
                    lines += pending.toString().trim()
                    pending.clear()
                }
                pending.length < longest -> pending.append(char)
            }
        }
        return lines
    }
}
