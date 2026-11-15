package com.dchernykh.chronometer.source.board

import android.os.SystemClock
import com.dchernykh.chronometer.source.BoardTrouble
import com.dchernykh.chronometer.source.CutoffSource
import com.dchernykh.chronometer.source.OpenFailure
import com.dchernykh.chronometer.source.SourceEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable
import java.io.IOException

/**
 * A beam board found: whether the app may use it, how to ask, how to open it, and what the
 * screen says while it may not ([refused]).
 */
class FoundBoard(
    val permitted: Boolean,
    val ask: () -> Unit,
    val open: () -> BoardPort,
    val refused: BoardTrouble = BoardTrouble.NoPermission,
)

/** Where beam boards are found: the phone's USB, its Bluetooth, a fake in tests. */
fun interface BoardPorts {
    fun find(): FoundBoard?

    /** Why [find] found none, for the screen. */
    fun missing(): BoardTrouble = BoardTrouble.NotFound

    /** Keep the phone's processor awake until closed: asleep, it would read the USB no more. */
    fun keepAwake(): Closeable = Closeable {}
}

/** A board that could not be opened, for a reason the screen says in the referee's language. */
class BoardNotOpened(
    val why: OpenFailure,
) : IOException(why.name)

/** A board's serial port. Both calls block, up to their timeouts; [read] may give nothing. */
interface BoardPort : Closeable {
    @Throws(IOException::class)
    fun read(timeoutMs: Int): ByteArray

    @Throws(IOException::class)
    fun write(
        bytes: ByteArray,
        timeoutMs: Int,
    )

    /** When the bytes [read] last gave came, where the link knows it better than the reader. */
    fun arrivedAt(): Long? = null
}

/** After a break, edges this soon are the same rider's: a bike's wheels and body. */
const val HOLD_OFF_MS = 1_000

private const val LOOK_AGAIN_MS = 2_000L
private const val ASK_AGAIN_MS = 1_000L

// Asked only this soon after timing mode starts: the referee has just started it.
private const val ASK_WITHIN_MS = 10_000L
private const val READ_MS = 200
private const val WRITE_MS = 1_000
private const val TICK_MS = 100L
private const val IDLE_MS = 5L

// The newline first ends a line the board may hold half of from before.
private val OPENING = "\nHOLDOFF $HOLD_OFF_MS\nHELLO\nSTATUS\n".toByteArray()

/**
 * The beam (spec FR-004): a beam board on the phone's USB, or over Bluetooth ([radio]), each
 * break timed by the board's own clock. It looks for the board every 2 s until one is found,
 * asks once for leave to use it, and opens it again once it is lost: the source never ends
 * by itself, and the screen says why no break can come meanwhile. [clock] is the phone's
 * wall clock, [monotonic] a clock no one sets, both in milliseconds.
 */
class BoardBeamSource(
    private val ports: BoardPorts,
    private val radio: Boolean = false,
    private val clock: () -> Long = System::currentTimeMillis,
    private val monotonic: () -> Long = SystemClock::elapsedRealtime,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : CutoffSource {
    // Carried over from one opening to the next, as the contract's steps 3 to 5 have it.
    private var nextPing = 1L
    private val counts = mutableMapOf<String, Counts>()

    override fun events(): Flow<SourceEvent> =
        channelFlow {
            // The phone in a pocket, its screen off: the board's lines must still be read, or
            // the board drops them once they wait half a second.
            val awake = ports.keepAwake()
            try {
                watch(Said(this))
            } finally {
                awake.close()
            }
        }

    private suspend fun ProducerScope<SourceEvent>.watch(board: Said) {
        val started = monotonic()
        var asked = false
        while (true) {
            val found = withContext(io) { ports.find() }
            when {
                found == null -> {
                    asked = false
                    board.say(ports.missing())
                    delay(LOOK_AGAIN_MS)
                }
                !found.permitted -> {
                    // Once a plugging in, and only as timing mode starts: mid-race, a question
                    // over the screen would stop the hand cutoffs until it was answered.
                    if (!asked && monotonic() - started < ASK_WITHIN_MS) found.ask()
                    asked = true
                    board.say(found.refused)
                    delay(ASK_AGAIN_MS)
                }
                else -> useBoard(found, board)
            }
        }
    }

    // Whatever the system's USB throws -- a SecurityException for leave taken back, say -- is
    // the board not opened, tried again in 2 s, not the source failed and paused for longer.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun ProducerScope<SourceEvent>.useBoard(
        found: FoundBoard,
        board: Said,
    ) {
        val port =
            try {
                withContext(io) { found.open() }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: BoardNotOpened) {
                board.say(BoardTrouble.NotOpened(failure.why))
                delay(LOOK_AGAIN_MS)
                return
            } catch (failure: Exception) {
                val words = failure.message ?: failure.javaClass.simpleName
                board.say(BoardTrouble.NotOpened(OpenFailure.OTHER, words))
                delay(LOOK_AGAIN_MS)
                return
            }
        try {
            session(port, board)
        } finally {
            withContext(NonCancellable + io) { port.close() }
        }
        board.say(BoardTrouble.Lost)
        delay(LOOK_AGAIN_MS)
    }

    /** One opening of the link, until it is lost: the board's lines, a PING now and then. */
    private suspend fun ProducerScope<SourceEvent>.session(
        port: BoardPort,
        board: Said,
    ) {
        // The board may have started again: its clock with it, so no old round trip counts.
        val bridge = BoardBridge(radio, nextPing, counts)
        try {
            coroutineScope {
                val lines = Channel<Pair<String, Long>>(Channel.UNLIMITED)
                launch(io) { read(port, lines) }
                write(port, OPENING)
                var pingAt = monotonic()
                do {
                    if (monotonic() >= pingAt) {
                        write(port, "${bridge.ping(clock())}\n".toByteArray())
                        pingAt = monotonic() + bridge.pingEveryMs
                    }
                    bridge.release(clock()).forEach { send(it) }
                    // RESEND: breaks counted while no link was up, or lost on the way.
                    while (bridge.outbox.isNotEmpty()) write(port, "${bridge.outbox.removeAt(0)}\n".toByteArray())
                    board.say(bridge.trouble(clock()))
                } while (take(lines, bridge))
                coroutineContext.cancelChildren()
            }
        } finally {
            nextPing = bridge.nextPing
        }
    }

    /** One line of the board's taken, or none within a tick; false once the port is gone. */
    private suspend fun ProducerScope<SourceEvent>.take(
        lines: Channel<Pair<String, Long>>,
        bridge: BoardBridge,
    ): Boolean {
        val got = withTimeoutOrNull(TICK_MS) { lines.receiveCatching() } ?: return true
        val (line, at) = got.getOrNull() ?: return false
        bridge.fromBoard(line, at).forEach { send(it) }
        return true
    }

    /** The port's lines into [lines], each timed as it came, until the port fails. */
    @Suppress("TooGenericExceptionCaught") // whatever ends the reading ends the link
    private suspend fun read(
        port: BoardPort,
        lines: Channel<Pair<String, Long>>,
    ) {
        val splitter = LineSplitter(firstPiece = radio)
        try {
            while (true) {
                val started = monotonic()
                val bytes = port.read(READ_MS)
                val at = port.arrivedAt() ?: clock()
                pass(bytes, at, splitter, lines, idlesAfter(bytes, monotonic() - started))
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            lines.close()
        }
    }

    private suspend fun pass(
        bytes: ByteArray,
        at: Long,
        splitter: LineSplitter,
        lines: Channel<Pair<String, Long>>,
        idle: Boolean,
    ) {
        when {
            bytes.isNotEmpty() -> splitter.feed(bytes, at).forEach { lines.send(it) }
            idle -> delay(IDLE_MS)
        }
    }

    private suspend fun write(
        port: BoardPort,
        bytes: ByteArray,
    ) {
        try {
            withContext(io) { port.write(bytes, WRITE_MS) }
        } catch (_: IOException) {
            // A board that reads no more: the reading side says when the port is gone, and
            // the unanswered PINGs that its program has stopped.
        }
    }
}

/**
 * Whether a read that gave [bytes] after [tookMs] pauses before the next. One that waited its
 * time out reads again at once, or a PONG coming meanwhile would be timed late; one back at
 * once with nothing is an error, or a fake in tests, and pauses rather than spin.
 */
internal fun idlesAfter(
    bytes: ByteArray,
    tookMs: Long,
): Boolean = bytes.isEmpty() && tookMs < READ_MS / 2

/** The board's state as last said: said again only once it changes. */
private class Said(
    private val events: ProducerScope<SourceEvent>,
) {
    private var last: BoardTrouble? = null

    suspend fun say(trouble: BoardTrouble?) {
        if (trouble != last) {
            last = trouble
            events.send(SourceEvent.Board(trouble))
        }
    }
}
