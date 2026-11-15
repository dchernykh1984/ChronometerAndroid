package com.dchernykh.chronometer.source.board

import com.dchernykh.chronometer.source.BoardTrouble
import com.dchernykh.chronometer.source.SourceEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val HELLO = "HELLO $BOARD_NAME $BOARD_FIRMWARE e6614103"

/** A board that has said its HELLO. */
private fun connected(): BoardBridge = BoardBridge().apply { fromBoard(HELLO, 0L) }

/** Its clock read too: one round trip, the board's 2 s at the phone's 1000 s. */
private fun BoardBridge.read(): BoardBridge =
    apply {
        val n = ping(1_000_000L).split(" ")[1]
        fromBoard("PONG $n 2000000", 1_000_000L)
    }

/**
 * The board's lines as the desktop connector reads them: the same cases as its tests,
 * ChronometerPython's tests/test_beam_connector.py, as both keep to one contract.
 */
class BoardBridgeTest {
    @Test
    fun aLineIsItsWordAndTheRest() {
        assertEquals("BREAK" to listOf("12", "812345"), parseLine("break 12  812345\r"))
        assertEquals("" to emptyList<String>(), parseLine("  "))
    }

    @Test
    fun aBreakOrPongWithoutTwoWholeNumbersCarriesNone() {
        val notTwo = listOf(listOf("1"), listOf("1", "2", "3"), listOf("1", "-2"), listOf("x", "2"), listOf("1.5", "2"))
        for (rest in notTwo) {
            assertNull(rest.toString(), twoNumbers(rest))
        }
        assertEquals(12L to 812_345L, twoNumbers(listOf("12", "812345")))
    }

    @Test
    fun theBoardClockMapsOntoTheMiddleOfARoundTrip() {
        val sync = ClockSync()
        assertNull(sync.toPhoneMs(2_000_000))
        sync.add(1_000_000.0, 1_000_004.0, 2_000_000)
        assertEquals(1_000_002L, sync.toPhoneMs(2_000_000))
        assertEquals(1_001_502L, sync.toPhoneMs(3_500_000))
    }

    @Test
    fun aRoundTripTooLongToSayWhenTheBoardAnsweredIsLeftOut() {
        val sync = ClockSync()
        sync.add(0.0, 20.1, 0)
        assertNull(sync.toPhoneMs(0))
        sync.add(0.0, 20.0, 0)
        assertEquals(10L, sync.toPhoneMs(0))
    }

    @Test
    fun theOffsetIsTheMedianOfTheLastNineRoundTrips() {
        val sync = ClockSync()
        (List(9) { 0.0 } + List(5) { 30.0 }).forEach { sync.add(it, it, 0) }
        assertEquals(30L, sync.toPhoneMs(0)) // five of the last nine: the oldest have gone
        sync.add(75.0, 75.0, 0) // a stray one, short of a clock step, moves it no further
        assertEquals(30L, sync.toPhoneMs(0))
    }

    @Test
    fun thePhoneClockSetAnewMovesTheMappingAtOnce() {
        val sync = ClockSync()
        repeat(9) { sync.add(it.toDouble(), it.toDouble(), it * 1_000L) } // offset 0
        sync.add(5_000.0, 5_000.0, 0) // the clock set 5 s on
        assertEquals(5_001L, sync.toPhoneMs(1_000))
    }

    @Test
    fun pingsAreNumberedOnAndUnansweredOnesMakeTheBoardSilent() {
        val bridge = BoardBridge()
        assertEquals("PING 1", bridge.ping(0L))
        repeat(40) { bridge.ping(it.toLong()) }
        assertEquals(42L, bridge.nextPing)
        assertEquals(BoardTrouble.Silent, bridge.trouble(SILENT_MS + 40)) // unanswered: still said
    }

    @Test
    fun aBoardThatAnswersNoPingForSixSecondsIsSilent() {
        val bridge = connected()
        bridge.ping(0L)
        assertNull(bridge.trouble(SILENT_MS))
        assertEquals(BoardTrouble.Silent, bridge.trouble(SILENT_MS + 1))
        bridge.ping(5_000L)
        bridge.fromBoard("PONG 2 10", 5_003L) // a later one answered: the board is there
        assertNull(bridge.trouble(1_000_000_000L))
    }

    @Test
    fun aBeamBlockedOrFlickeringLongerThanARiderTakesIsSaidUntilItIsBack() {
        val bridge = connected()
        bridge.fromBoard("STATUS blocked", 1_000L)
        bridge.fromBoard("STATUS blocked", 1_500L) // said again when asked: since the first
        assertNull(bridge.trouble(1_000L + DARK_MS))
        assertEquals(BoardTrouble.Blocked, bridge.trouble(1_000L + DARK_MS + 1))
        bridge.fromBoard("STATUS unsteady", 4_000L)
        assertEquals(BoardTrouble.Unsteady, bridge.trouble(4_000L + DARK_MS + 1))
        bridge.fromBoard("STATUS beam", 9_000L)
        assertNull(bridge.trouble(1_000_000_000L))
    }

    @Test
    fun aBoardOnAnotherFirmwareIsSaidAndItsBreaksStillCount() {
        val bridge = BoardBridge()
        assertEquals(
            listOf(SourceEvent.Board(BoardTrouble.OtherFirmware("0.1", BOARD_FIRMWARE))),
            bridge.fromBoard("HELLO $BOARD_NAME 0.1 e6614103", 0L),
        )
        assertEquals(1, bridge.read().fromBoard("BREAK 1 1000", 0L).size)
        assertTrue(BoardBridge().fromBoard(HELLO, 0L).isEmpty()) // the same: nothing to say
    }

    @Test
    fun aHelloShortOfWordsIsTakenWithoutFallingOver() {
        val bridge = BoardBridge()
        assertEquals(
            listOf(SourceEvent.Board(BoardTrouble.OtherFirmware("?", BOARD_FIRMWARE))),
            bridge.fromBoard("HELLO $BOARD_NAME", 0L),
        )
        assertEquals(BOARD_NAME, bridge.board)
    }

    @Test
    fun aBreakBeforeTheHelloWaitsForTheBoardsClockNotDropped() {
        val bridge = BoardBridge()
        assertTrue(bridge.fromBoard("BREAK 1 2500000", 5L).isEmpty())
        assertTrue(bridge.fromBoard("HELLO another-board 1", 5L).isEmpty())
        assertTrue(bridge.fromBoard(HELLO, 6L).isEmpty())
        val n = bridge.ping(1_000_000L).split(" ")[1]
        assertEquals(
            listOf(SourceEvent.BeamBreak(1, 1_000_500L, "2500000")),
            bridge.fromBoard("PONG $n 2000000", 1_000_000L),
        )
    }

    @Test
    fun aBoardThatNeverSaysHelloHasNoBreakCounted() {
        val bridge = BoardBridge()
        bridge.fromBoard("BREAK 1 1000", 0L)
        bridge.fromBoard("PONG 1 1000", 0L)
        assertTrue(bridge.release(Long.MAX_VALUE).isEmpty())
    }

    @Test
    fun aBreakGivenUpOnForTheBoardsClockTakesItsLinesTimeAndSaysSo() {
        val bridge = connected()
        val came = 1_761_725_401_500L
        assertTrue(bridge.fromBoard("BREAK 12 812345", came).isEmpty())
        assertTrue(bridge.release(came + HELD_MS).isEmpty())
        assertEquals(
            listOf(
                SourceEvent.BeamBreak(12, came, "812345"),
                SourceEvent.Board(BoardTrouble.ClockNotRead(1)),
            ),
            bridge.release(came + HELD_MS + 1),
        )
    }

    @Test
    fun aBreakHasTheBoardTimeMovedOntoThePhoneClock() {
        val bridge = connected().read()
        assertEquals(
            listOf(SourceEvent.BeamBreak(3, 1_001_500L, "3500000")),
            bridge.fromBoard("BREAK 3 3500000", 9_999_999L), // its line came late
        )
    }

    @Test
    fun breaksTheBoardCountedThatNeverCameAreSaid() {
        val bridge = connected().read()
        assertEquals(1, bridge.fromBoard("BREAK 4 1000", 0L).size) // the first seen: no gap
        assertEquals(1, bridge.fromBoard("BREAK 5 2000", 0L).size)
        assertEquals(SourceEvent.Board(BoardTrouble.BreaksMissing(6, 7)), bridge.fromBoard("BREAK 8 3000", 0L)[1])
        assertEquals(SourceEvent.Board(BoardTrouble.BreaksMissing(9, 9)), bridge.fromBoard("BREAK 10 4000", 0L)[1])
        assertEquals(1, bridge.fromBoard("BREAK 1 5000", 0L).size) // the board started again
    }

    @Test
    fun theLastBreakOfABoardIsKeptOverAnOpeningOfItsPortAgain() {
        val kept = mutableMapOf<String, Long>()
        BoardBridge(lastBreaks = kept).apply { fromBoard(HELLO, 0L) }.read().fromBoard("BREAK 1 1000", 0L)
        val again = BoardBridge(lastBreaks = kept).apply { fromBoard(HELLO, 0L) }.read()
        assertEquals(SourceEvent.Board(BoardTrouble.BreaksMissing(2, 2)), again.fromBoard("BREAK 3 2000", 0L)[1])
    }

    @Test
    fun aBoardErrorIsSaidAndItsOtherLinesAreItsOwnBusiness() {
        val bridge = connected()
        assertEquals(listOf(SourceEvent.Error("board: unknown: XYZ")), bridge.fromBoard("ERROR unknown: XYZ", 0L))
        for (line in listOf("STATUS beam", "FLASH 1 5", "HOLDOFF 300", "", "BREAK x 1", ">>> PING 3")) {
            assertTrue(line, bridge.fromBoard(line, 0L).isEmpty())
        }
    }

    @Test
    fun onlyALineWithItsEndCounts() {
        val splitter = LineSplitter()
        assertEquals(listOf("HELLO beam-rp2040"), splitter.feed("HELLO beam-rp2040\r\nBREAK 5 12".toByteArray()))
        assertEquals(listOf("BREAK 5 12345678"), splitter.feed("345678\r\n".toByteArray()))
        assertTrue(LineSplitter().feed("BREAK 5 12".toByteArray()).isEmpty()) // cut short: no line
    }
}
