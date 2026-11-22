package com.dchernykh.chronometer.source.board

import com.dchernykh.chronometer.source.BoardTrouble
import com.dchernykh.chronometer.source.OpenFailure
import com.dchernykh.chronometer.source.SourceEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

private const val BASE_MS = 1_767_261_600_000L
private const val HELLO = "HELLO beam-rp2040 $BOARD_FIRMWARE e6614103"

/**
 * A beam board running its firmware on a port: it answers as beamcore.py does, at board time
 * 2 s, keeps its breaks for RESEND, and says when each of its lines came, as a radio does.
 */
private class FakePort : BoardPort {
    val written = mutableListOf<String>()
    private val said = ArrayDeque<ByteArray>()
    val kept = mutableListOf<String>()
    var arrived: Long? = null // what arrivedAt() says, as Bluetooth's port does
    var gone = false
    var breaks: RuntimeException? = null // what the system's USB may throw instead
    var answering = true
    var closed = false

    fun say(line: String) {
        if (line.startsWith("BREAK")) kept += line
        said.addLast("$line\r\n".toByteArray())
    }

    override fun arrivedAt(): Long? = arrived

    override fun read(timeoutMs: Int): ByteArray {
        if (gone) throw IOException("the beam board was unplugged")
        breaks?.let { throw it }
        return said.removeFirstOrNull() ?: ByteArray(0)
    }

    override fun write(
        bytes: ByteArray,
        timeoutMs: Int,
    ) {
        for (line in String(bytes).split("\n").dropLast(1)) {
            written += line
            when {
                line == "HELLO" -> say("$HELLO ${kept.lastOrNull()?.split(" ")?.get(1) ?: "0"}")
                line == "STATUS" -> say("STATUS beam")
                line.startsWith("PING ") && answering -> say("PONG ${line.split(" ")[1]} 2000000 2000000")
                line.startsWith("RESEND ") ->
                    kept.filter { it.split(" ")[1].toLong() >= line.split(" ")[1].toLong() }.forEach {
                        said.addLast("$it\r\n".toByteArray())
                    }
            }
        }
    }

    override fun close() {
        closed = true
    }
}

/** The phone's USB: a board or none, the app let use it or not. */
private class FakeUsb {
    var plugged = true
    var permitted = true
    var missing: BoardTrouble = BoardTrouble.NotFound // why none is found, as the ports say it
    var refused: BoardTrouble = BoardTrouble.NoPermission
    var answering = true // the boards to come answer PING
    var asked = 0
    var openFails: String? = null
    var openThrows: Exception? = null
    val ports = mutableListOf<FakePort>()
    var awake = 0 // the processor's keep-awake locks held

    val boards =
        object : BoardPorts {
            override fun find(): FoundBoard? =
                if (!plugged) {
                    null
                } else {
                    FoundBoard(
                        permitted = permitted,
                        refused = refused,
                        ask = { asked++ },
                        open = {
                            openFails?.let { throw IOException(it) }
                            openThrows?.let { throw it }
                            FakePort().also {
                                it.answering = answering
                                ports += it
                            }
                        },
                    )
                }

            override fun missing(): BoardTrouble = missing

            override fun keepAwake(): java.io.Closeable {
                awake++
                return java.io.Closeable { awake-- }
            }
        }
}

@OptIn(ExperimentalCoroutinesApi::class)
class BoardBeamSourceTest {
    private var collecting: Job? = null

    private fun TestScope.running(
        usb: FakeUsb,
        radio: Boolean = false,
    ): MutableList<SourceEvent> {
        val io = StandardTestDispatcher(testScheduler)
        val source =
            BoardBeamSource(
                usb.boards,
                radio = radio,
                clock = { BASE_MS + testScheduler.currentTime },
                monotonic = { testScheduler.currentTime },
                io = io,
            )
        val events = mutableListOf<SourceEvent>()
        collecting = backgroundScope.launch(io) { source.events().toList(events) }
        runCurrent()
        return events
    }

    @Test
    fun theProcessorIsKeptAwakeWhileTheUsbBeamRunsAndLetSleepOnceItStops() =
        runTest {
            val usb = FakeUsb()
            running(usb)
            advanceTimeBy(500)
            assertEquals(1, usb.awake)
            collecting!!.cancel()
            advanceTimeBy(500)
            assertEquals(0, usb.awake)
            assertTrue(usb.ports.single().closed)
        }

    private fun troubles(events: List<SourceEvent>) = events.filterIsInstance<SourceEvent.Board>().map { it.trouble }

    @Test
    fun aBreakOnTheBoardComesTimedByTheBoardsClock() =
        runTest {
            val usb = FakeUsb()
            val events = running(usb)
            advanceTimeBy(500)
            val port = usb.ports.single()
            assertEquals(listOf("", "HOLDOFF $HOLD_OFF_MS", "HELLO", "STATUS", "PING 1"), port.written.take(5))
            val answeredAt = BASE_MS + 0 // PING 1 went out and came back at once
            port.say("BREAK 1 2500000") // half a second after the board answered PING 1
            advanceTimeBy(500)
            assertEquals(
                listOf(SourceEvent.BeamBreak(1, answeredAt + 500, "2500000")),
                events.filterIsInstance<SourceEvent.BeamBreak>(),
            )
            assertTrue(troubles(events).isEmpty()) // nothing went wrong, nothing said
        }

    @Test
    fun aReadThatWaitedItsTimeOutReadsAgainAtOnceAndOneBackAtOnceWithNothingPauses() {
        assertTrue(idlesAfter(ByteArray(0), 0)) // an error, or a fake: no spinning
        assertFalse(idlesAfter(ByteArray(0), 200)) // its 200 ms waited: a PONG may come now
        assertFalse(idlesAfter("PONG 1 5\r\n".toByteArray(), 0))
    }

    @Test
    fun noBoardIsSaidOnceAndOnePluggedInIsTaken() =
        runTest {
            val usb = FakeUsb().apply { plugged = false }
            val events = running(usb)
            advanceTimeBy(7_000)
            assertEquals(listOf(BoardTrouble.NotFound), troubles(events))
            usb.plugged = true
            advanceTimeBy(2_500)
            assertEquals(listOf(BoardTrouble.NotFound, null), troubles(events))
            assertEquals(1, usb.ports.size)
        }

    @Test
    fun aBoardTheAppMayNotUseIsAskedForOnceAPluggingIn() =
        runTest {
            val usb = FakeUsb().apply { permitted = false }
            val events = running(usb)
            advanceTimeBy(5_000)
            assertEquals(1, usb.asked)
            assertEquals(listOf(BoardTrouble.NoPermission), troubles(events))
            usb.permitted = true
            advanceTimeBy(1_500)
            assertEquals(listOf(BoardTrouble.NoPermission, null), troubles(events))
            assertEquals(1, usb.ports.size)
        }

    @Test
    fun aBoardPluggedInMidRaceIsNotAskedForOverTheScreen() =
        runTest {
            val usb = FakeUsb().apply { plugged = false }
            val events = running(usb)
            advanceTimeBy(20_000) // the race on, the board not yet in
            usb.plugged = true
            usb.permitted = false
            advanceTimeBy(5_000)
            assertEquals(0, usb.asked) // a question would cover the Cutoff button
            assertEquals(listOf(BoardTrouble.NotFound, BoardTrouble.NoPermission), troubles(events))
        }

    @Test
    fun aBoardPluggedInAgainIsAskedForAgain() =
        runTest {
            val usb = FakeUsb().apply { permitted = false }
            running(usb)
            advanceTimeBy(3_000)
            usb.plugged = false // the referee answered no, and plugs the board in again
            advanceTimeBy(2_500)
            usb.plugged = true
            advanceTimeBy(2_500)
            assertEquals(2, usb.asked)
        }

    @Test
    fun aBoardThatCannotBeOpenedIsSaidByItsReasonOrTheSystemsWords() =
        runTest {
            val usb = FakeUsb().apply { openThrows = BoardNotOpened(OpenFailure.HELD) }
            val events = running(usb)
            advanceTimeBy(1_000)
            usb.openThrows = null
            usb.openFails = "I/O error"
            advanceTimeBy(2_500)
            assertEquals(
                listOf(
                    BoardTrouble.NotOpened(OpenFailure.HELD),
                    BoardTrouble.NotOpened(OpenFailure.OTHER, "I/O error"),
                ),
                troubles(events),
            )
        }

    @Test
    fun whatTheSystemsUsbThrowsIsTheBoardNotOpenedOrLostAndTriedAgain() =
        runTest {
            val usb = FakeUsb().apply { openThrows = SecurityException("no leave for the device") }
            val events = running(usb)
            advanceTimeBy(1_000)
            val notOpened = BoardTrouble.NotOpened(OpenFailure.OTHER, "no leave for the device")
            assertEquals(listOf(notOpened), troubles(events))
            usb.openThrows = null
            advanceTimeBy(2_500)
            usb.ports[0].breaks = IllegalStateException("the connection was closed")
            advanceTimeBy(500)
            assertEquals(
                listOf(notOpened, null, BoardTrouble.Lost),
                troubles(events),
            )
            advanceTimeBy(2_500)
            assertEquals(2, usb.ports.size) // and opened again, the source never failed
        }

    @Test
    fun aBoardLostIsSaidAndOpenedAgainItsPingsCountingOn() =
        runTest {
            val usb = FakeUsb()
            val events = running(usb)
            advanceTimeBy(500)
            usb.ports[0].gone = true
            advanceTimeBy(500)
            assertEquals(listOf(BoardTrouble.Lost), troubles(events))
            assertTrue(usb.ports[0].closed)
            advanceTimeBy(2_500)
            assertEquals(listOf(BoardTrouble.Lost, null), troubles(events))
            assertEquals("PING 2", usb.ports[1].written[4]) // a number once used is not used again
        }

    @Test
    fun aBoardThatStopsAnsweringIsSaidUntilItAnswers() =
        runTest {
            val usb = FakeUsb()
            val events = running(usb)
            advanceTimeBy(500)
            usb.ports[0].answering = false
            advanceTimeBy(SILENT_MS + PING_EVERY_MS)
            assertEquals(listOf(BoardTrouble.Silent), troubles(events))
            usb.ports[0].answering = true
            advanceTimeBy(PING_EVERY_MS + 500)
            assertEquals(listOf(BoardTrouble.Silent, null), troubles(events))
        }

    @Test
    fun aBlockedBeamIsSaidAndSoIsItsReturn() =
        runTest {
            val usb = FakeUsb()
            val events = running(usb)
            advanceTimeBy(500)
            usb.ports[0].say("STATUS blocked")
            advanceTimeBy(DARK_MS + 500)
            assertEquals(listOf(BoardTrouble.Blocked), troubles(events))
            usb.ports[0].say("STATUS beam")
            advanceTimeBy(500)
            assertEquals(listOf(BoardTrouble.Blocked, null), troubles(events))
        }

    @Test
    fun theScreenSaysWhyNoBoardIsFoundAndWhyItMayNotBeUsed() =
        runTest {
            val usb = FakeUsb().apply { plugged = false }
            usb.missing = BoardTrouble.NotNamed
            val events = running(usb)
            advanceTimeBy(500)
            assertEquals(listOf(BoardTrouble.NotNamed), troubles(events))
            usb.plugged = true
            usb.permitted = false
            usb.refused = BoardTrouble.NoBluetoothLeave
            advanceTimeBy(2_000)
            assertEquals(listOf(BoardTrouble.NotNamed, BoardTrouble.NoBluetoothLeave), troubles(events))
        }

    @Test
    fun overBluetoothTheBoardIsPingedTwiceASecond() =
        runTest {
            val usb = FakeUsb()
            running(usb, radio = true)
            advanceTimeBy(1_750)
            val pings = usb.ports[0].written.filter { it.startsWith("PING") }
            assertEquals(listOf("PING 1", "PING 2", "PING 3", "PING 4"), pings)
        }

    @Test
    fun aBreakTheBoardSawWhileItsLineWasLostComesWhenAskedForAgain() =
        runTest {
            val usb = FakeUsb()
            val events = running(usb)
            advanceTimeBy(500)
            val port = usb.ports[0]
            port.say("BREAK 1 2500000")
            advanceTimeBy(500)
            port.kept += "BREAK 2 2600000" // said, but lost on the way
            port.say("BREAK 3 2700000")
            advanceTimeBy(500)
            assertTrue("RESEND 2" in port.written)
            val seqs = events.filterIsInstance<SourceEvent.BeamBreak>().map { it.seq }
            assertEquals(listOf(1L, 3L, 2L), seqs)
            assertEquals(BASE_MS + 600, events.filterIsInstance<SourceEvent.BeamBreak>().last().timeMs)
        }

    @Test
    fun aLineIsTimedWhenItsLinkSaysItCame() =
        runTest {
            val usb = FakeUsb().apply { answering = false } // no clock: a break takes its line's time
            val events = running(usb)
            advanceTimeBy(500)
            val port = usb.ports[0]
            port.arrived = BASE_MS + 123
            port.say("BREAK 1 2500000")
            advanceTimeBy(HELD_MS + 500)
            assertEquals(BASE_MS + 123, events.filterIsInstance<SourceEvent.BeamBreak>().single().timeMs)
        }

    @Test
    fun theBoardIsAskedItsCountEveryFewSecondsALostLastBreakComes() =
        runTest {
            val usb = FakeUsb()
            val events = running(usb)
            advanceTimeBy(500)
            val port = usb.ports[0]
            port.kept += "BREAK 1 2500000" // said, its line lost: no break comes after it
            advanceTimeBy(HELLO_EVERY_MS)
            assertTrue("RESEND 1" in port.written)
            assertEquals(listOf(1L), events.filterIsInstance<SourceEvent.BeamBreak>().map { it.seq })
        }
}
