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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val BASE_MS = 1_767_261_600_000L

/** A board on a link: its HELLO gives [id], it answers PING at its 2 s, and keeps what it is sent. */
private class LinkedBoard(
    val id: String,
) : BoardPort {
    val written = mutableListOf<String>()
    private val said = ArrayDeque<ByteArray>()
    var closed = false

    fun say(line: String) {
        said.addLast("$line\r\n".toByteArray())
    }

    override fun read(timeoutMs: Int): ByteArray = said.removeFirstOrNull() ?: ByteArray(0)

    override fun write(
        bytes: ByteArray,
        timeoutMs: Int,
    ) {
        for (line in String(bytes).split("\n").dropLast(1)) {
            written += line
            when {
                line == "HELLO" -> say("HELLO beam-esp32c3 $BOARD_FIRMWARE $id 0")
                line.startsWith("PING ") -> say("PONG ${line.split(" ")[1]} 2000000 2000000")
            }
        }
    }

    override fun close() {
        closed = true
    }
}

private val PICK_A = BoardPick(BoardLink.BLE, "AA", "beam-aaaa")
private val PICK_B = BoardPick(BoardLink.USB, USB_KEY, "beam-rp2040")

@OptIn(ExperimentalCoroutinesApi::class)
class BoardsSourceTest {
    private fun TestScope.boards(
        vararg links: Pair<BoardPick, LinkedBoard>,
    ): Pair<BoardsSource, MutableList<SourceEvent>> {
        val io = StandardTestDispatcher(testScheduler)
        val ports = links.toMap()
        val source =
            BoardsSource(links.map { it.first }) { pick, claim ->
                val board = ports.getValue(pick)
                BoardBeamSource(
                    { FoundBoard(permitted = true, ask = {}, open = { board }) },
                    clock = { BASE_MS + testScheduler.currentTime },
                    monotonic = { testScheduler.currentTime },
                    io = io,
                    picked = claim,
                )
            }
        val events = mutableListOf<SourceEvent>()
        backgroundScope.launch(io) { source.events().toList(events) }
        runCurrent()
        return source to events
    }

    @Test
    fun everyBreakOfEveryBoardIsACutoffOfItsOwnOnItsOwnClock() =
        runTest {
            val a = LinkedBoard("aaaa")
            val b = LinkedBoard("bbbb")
            val (_, events) = boards(PICK_A to a, PICK_B to b)
            advanceTimeBy(500)
            a.say("BREAK 1 2500000")
            b.say("BREAK 1 2600000") // the same count, another board: a cutoff too
            advanceTimeBy(500)
            assertEquals(
                listOf(BASE_MS + 500, BASE_MS + 600),
                events.filterIsInstance<SourceEvent.BeamBreak>().map { it.timeMs }.sortedBy { it },
            )
            // Each board's first state said, all well: the screen shows both connected.
            val named = events.filterIsInstance<SourceEvent.Board>()
            assertEquals(
                setOf("beam-aaaa" to null, "beam-rp2040" to null),
                named.map { it.board to it.trouble }.toSet(),
            )
        }

    @Test
    fun oneBoardPickedOnTwoLinksIsReadOnTheFirstOnly() =
        runTest {
            val usb = LinkedBoard("aaaa")
            val radio = LinkedBoard("aaaa") // the same board, on its Bluetooth
            val (_, events) = boards(PICK_B to usb, PICK_A to radio)
            advanceTimeBy(500)
            val twins = events.filterIsInstance<SourceEvent.Board>().filter { it.trouble is BoardTrouble.Twin }
            assertEquals(1, twins.size)
            val dropped = if (twins.single().board == "beam-aaaa") radio else usb
            val kept = if (dropped === radio) usb else radio
            assertTrue(dropped.closed)
            kept.say("BREAK 1 2500000")
            dropped.say("BREAK 1 2500000") // let go: never read
            advanceTimeBy(500)
            assertEquals(1, events.filterIsInstance<SourceEvent.BeamBreak>().size)
        }

    @Test
    fun aBoardConnectedBlinksFiveTimesAndOneNotIsSaidSo() =
        runTest {
            val a = LinkedBoard("aaaa")
            val (source, _) = boards(PICK_A to a)
            assertNull(source.blink("nope"))
            advanceTimeBy(500)
            assertEquals(true, source.blink("AA"))
            advanceTimeBy(FLASHES * FLASH_EVERY_MS)
            assertEquals((1..FLASHES).map { "FLASH $it" }, a.written.filter { it.startsWith("FLASH") })
        }

    @Test
    fun aBoardNotConnectedYetBlinksNot() =
        runTest {
            val io = StandardTestDispatcher(testScheduler)
            val source =
                BoardsSource(listOf(PICK_A)) { _, claim ->
                    BoardBeamSource({ null }, io = io, monotonic = { testScheduler.currentTime }, picked = claim)
                }
            assertFalse(source.blink("AA")!!)
        }

    @Test
    fun noBoardPickedIsSaid() =
        runTest {
            val (_, events) = boards()
            assertEquals(listOf(SourceEvent.Board(BoardTrouble.NoBoardPicked)), events)
        }
}
