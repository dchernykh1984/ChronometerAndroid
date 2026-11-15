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

/** A beam board found on USB: whether the app may use it, how to ask, how to open it. */
class FoundBoard(
    val permitted: Boolean,
    val ask: () -> Unit,
    val open: () -> BoardPort,
)

/** Where beam boards are found: the phone's USB, a fake in tests. */
fun interface BoardPorts {
    fun find(): FoundBoard?

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
}

/** After a break, edges this soon are the same rider's: a bike's wheels and body. */
const val HOLD_OFF_MS = 1_000

private const val LOOK_AGAIN_MS = 2_000L
private const val ASK_AGAIN_MS = 1_000L
private const val READ_MS = 200
private const val WRITE_MS = 1_000
private const val TICK_MS = 100L
private const val IDLE_MS = 5L

// The newline first ends a line the board may hold half of from before.
private val OPENING = "\nHOLDOFF $HOLD_OFF_MS\nHELLO\nSTATUS\n".toByteArray()

/**
 * The USB beam (spec FR-004): the beam board on the phone's USB, each break timed by the
 * board's own clock. It looks for the board every 2 s until one is plugged in, asks once
 * for leave to use it, and opens it again once it is lost: the source never ends by
 * itself, and the screen says why no break can come meanwhile. [clock] is the phone's
 * wall clock, [monotonic] a clock no one sets, both in milliseconds.
 */
class UsbBeamSource(
    private val ports: BoardPorts,
    private val clock: () -> Long = System::currentTimeMillis,
    private val monotonic: () -> Long = SystemClock::elapsedRealtime,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : CutoffSource {
    // Carried over from one opening to the next, as the contract's steps 3 and 4 have it.
    private var nextPing = 1L
    private val lastBreaks = mutableMapOf<String, Long>()

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
        var asked = false
        while (true) {
            val found = withContext(io) { ports.find() }
            when {
                found == null -> {
                    asked = false
                    board.say(BoardTrouble.NotFound)
                    delay(LOOK_AGAIN_MS)
                }
                !found.permitted -> {
                    if (!asked) found.ask() // once a plugging in: its question stays on screen
                    asked = true
                    board.say(BoardTrouble.NoPermission)
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

    /** One opening of the port, until it is lost: the board's lines, a PING every 2 s. */
    private suspend fun ProducerScope<SourceEvent>.session(
        port: BoardPort,
        board: Said,
    ) {
        // The board may have started again: its clock with it, so no old round trip counts.
        val bridge = BoardBridge(nextPing, lastBreaks)
        try {
            coroutineScope {
                val lines = Channel<Pair<String, Long>>(Channel.UNLIMITED)
                launch(io) { read(port, lines) }
                write(port, OPENING)
                var pingAt = monotonic()
                do {
                    if (monotonic() >= pingAt) {
                        write(port, "${bridge.ping(clock())}\n".toByteArray())
                        pingAt = monotonic() + PING_EVERY_MS
                    }
                    bridge.release(clock()).forEach { send(it) }
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
        val splitter = LineSplitter()
        try {
            while (true) pass(port.read(READ_MS), splitter, lines)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            lines.close()
        }
    }

    private suspend fun pass(
        bytes: ByteArray,
        splitter: LineSplitter,
        lines: Channel<Pair<String, Long>>,
    ) {
        if (bytes.isEmpty()) delay(IDLE_MS) else splitter.feed(bytes).forEach { lines.send(it to clock()) }
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
