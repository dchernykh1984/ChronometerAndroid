package com.dchernykh.chronometer.source.board

import com.dchernykh.chronometer.source.BoardTrouble
import com.dchernykh.chronometer.source.SourceEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

private const val BASE_MS = 1_767_261_600_000L
private const val HELLO = "HELLO $BOARD_NAME $BOARD_FIRMWARE e6614103"

/** A beam board running its firmware on a port: it answers as main.py does, at board time 2 s. */
private class FakePort : BoardPort {
    val written = mutableListOf<String>()
    private val said = ArrayDeque<ByteArray>()
    var gone = false
    var breaks: RuntimeException? = null // what the system's USB may throw instead
    var answering = true
    var closed = false

    fun say(line: String) {
        said.addLast("$line\r\n".toByteArray())
    }

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
                line == "HELLO" -> say(HELLO)
                line == "STATUS" -> say("STATUS beam")
                line.startsWith("PING ") && answering -> say("PONG ${line.split(" ")[1]} 2000000")
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
    var asked = 0
    var openFails: String? = null
    var openThrows: RuntimeException? = null
    val ports = mutableListOf<FakePort>()

    val boards =
        BoardPorts {
            if (!plugged) {
                null
            } else {
                FoundBoard(
                    permitted = permitted,
                    ask = { asked++ },
                    open = {
                        openFails?.let { throw IOException(it) }
                        openThrows?.let { throw it }
                        FakePort().also { ports += it }
                    },
                )
            }
        }
}

@OptIn(ExperimentalCoroutinesApi::class)
class UsbBeamSourceTest {
    private fun TestScope.running(usb: FakeUsb): MutableList<SourceEvent> {
        val io = StandardTestDispatcher(testScheduler)
        val source =
            UsbBeamSource(usb.boards, { BASE_MS + testScheduler.currentTime }, { testScheduler.currentTime }, io)
        val events = mutableListOf<SourceEvent>()
        backgroundScope.launch(io) { source.events().toList(events) }
        runCurrent()
        return events
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
    fun aBoardThatCannotBeOpenedIsSaidWithTheSystemsWords() =
        runTest {
            val usb = FakeUsb().apply { openFails = "another app holds the beam board" }
            val events = running(usb)
            advanceTimeBy(5_000)
            assertEquals(listOf(BoardTrouble.NotOpened("another app holds the beam board")), troubles(events))
        }

    @Test
    fun whatTheSystemsUsbThrowsIsTheBoardNotOpenedOrLostAndTriedAgain() =
        runTest {
            val usb = FakeUsb().apply { openThrows = SecurityException("no leave for the device") }
            val events = running(usb)
            advanceTimeBy(1_000)
            assertEquals(listOf(BoardTrouble.NotOpened("no leave for the device")), troubles(events))
            usb.openThrows = null
            advanceTimeBy(2_500)
            usb.ports[0].breaks = IllegalStateException("the connection was closed")
            advanceTimeBy(500)
            assertEquals(
                listOf(BoardTrouble.NotOpened("no leave for the device"), null, BoardTrouble.Lost),
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
}
