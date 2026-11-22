package com.dchernykh.chronometer.source.board

import com.dchernykh.chronometer.source.BoardTrouble
import com.dchernykh.chronometer.source.SourceEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val HELLO = "HELLO beam-rp2040 $BOARD_FIRMWARE e6614103 0"

/** The board's HELLO with the count of its last break. */
private fun hello(count: Long): String = "${HELLO.substringBeforeLast(' ')} $count"

/** A board that has said its HELLO. */
private fun connected(
    radio: Boolean = false,
    line: String = HELLO,
): BoardBridge = BoardBridge(radio).apply { fromBoard(line, 0L) }

/** Its clock read too: round trips with the board's 2 s at the phone's 1000 s. */
private fun BoardBridge.read(): BoardBridge =
    apply {
        repeat(if (radio) 4 else 1) {
            val n = ping(1_000_000L).split(" ")[1]
            fromBoard("PONG $n 2000000 2000000", 1_000_000L)
        }
    }

/** Round trips over a radio: each way waits the ms given; the phone [offsetMs] ahead. */
private fun radioTrips(
    offsetMs: Double,
    waits: List<Pair<Double, Double>>,
): RadioSync =
    RadioSync().apply {
        var sent = 1_000_000.0
        for ((there, back) in waits) {
            val gotUs = ((sent + there - offsetMs) * 1_000).toLong()
            val sentUs = gotUs + 12_000 // the board's random wait before its PONG
            add(sent, sentUs / 1_000.0 + offsetMs + back, gotUs, sentUs)
            sent += 1_000.0
        }
    }

/**
 * The boards' lines as the desktop connector reads them: the same cases as its tests,
 * ChronometerPython's tests/test_beam_board.py, as both keep to one contract (0.4).
 */
class BoardBridgeTest {
    @Test
    fun aLineIsItsWordAndTheRest() {
        assertEquals("BREAK" to listOf("12", "812345"), parseLine("break 12  812345\r"))
        assertEquals("" to emptyList<String>(), parseLine("  "))
    }

    @Test
    fun aBreakOrPongWithoutItsWholeNumbersCarriesNone() {
        val notTwo = listOf(listOf("1"), listOf("1", "2", "3"), listOf("1", "-2"), listOf("x", "2"), listOf("1.5", "2"))
        for (rest in notTwo) {
            assertNull(rest.toString(), wholeNumbers(rest, 2))
        }
        assertEquals(listOf(12L, 812_345L), wholeNumbers(listOf("12", "812345"), 2))
        assertEquals(listOf(7L, 10L, 22L), wholeNumbers(listOf("7", "10", "22"), 3))
        assertNull(wholeNumbers(listOf("99999999999999999999", "1"), 2)) // past a Long
    }

    @Test
    fun theBoardClockMapsOntoTheMiddleOfARoundTripItsWaitLeftOut() {
        // The board waited 10 ms: the trip took 14, 4 of them on the wire, its moment their middle.
        val sync = ClockSync()
        assertNull(sync.toPhoneMs(2_000_000))
        sync.add(1_000_000.0, 1_000_014.0, 2_000_000, 2_010_000)
        assertEquals(1_000_002L, sync.toPhoneMs(2_000_000))
        assertEquals(1_001_502L, sync.toPhoneMs(3_500_000))
    }

    @Test
    fun aRoundTripTooLongToSayWhenTheBoardAnsweredIsLeftOut() {
        val sync = ClockSync()
        sync.add(0.0, 20.1, 0, 0)
        assertNull(sync.toPhoneMs(0))
        sync.add(0.0, 20.0, 0, 0)
        assertEquals(10L, sync.toPhoneMs(0))
    }

    @Test
    fun theOffsetIsTheMedianOfTheLastNineRoundTrips() {
        val sync = ClockSync()
        (List(9) { 0.0 } + List(5) { 30.0 }).forEach { sync.add(it, it, 0, 0) }
        assertEquals(30L, sync.toPhoneMs(0)) // five of the last nine: the oldest have gone
        sync.add(75.0, 75.0, 0, 0) // a stray one, short of a clock step, moves it no further
        assertEquals(30L, sync.toPhoneMs(0))
    }

    @Test
    fun thePhoneClockSetAnewMovesTheMappingAtOnce() {
        val sync = ClockSync()
        repeat(9) { sync.add(it.toDouble(), it.toDouble(), it * 1_000L, it * 1_000L) } // offset 0
        sync.add(5_000.0, 5_000.0, 0, 0) // the clock set 5 s on
        assertEquals(5_001L, sync.toPhoneMs(1_000))
    }

    @Test
    fun overBluetoothTheShortestTripEachWayGivesTheClock() {
        // A PONG sent at once would always wait a whole connection event (30 ms): the middle
        // of the round trip would be 15 ms off. The shortest way there and back are not.
        val waits = listOf(29.0 to 30.0, 3.0 to 30.0, 17.0 to 2.5, 25.0 to 11.0, 8.0 to 30.0)
        val sync = radioTrips(500_000.0, waits)
        assertEquals(500_000.0 + (2.5 - 3.0) / 2, sync.offset, 1e-6)
        assertEquals(Math.round(2_000 + sync.offset), sync.toPhoneMs(2_000_000))
    }

    @Test
    fun theRadioClockIsReadOnlyOnceEnoughTripsHaveCome() {
        val short = radioTrips(0.0, List(3) { 5.0 to 5.0 })
        assertFalse(short.read)
        assertNull(short.toPhoneMs(1_000))
        val enough = radioTrips(0.0, List(4) { 5.0 to 5.0 })
        assertTrue(enough.read)
        assertEquals(1L, enough.toPhoneMs(1_000))
    }

    @Test
    fun theRadioClockStartsAgainOnAClockStep() {
        val sync = radioTrips(0.0, listOf(1.0 to 1.0) + List(RADIO_SAMPLES) { 9.0 to 9.0 })
        assertEquals(0.0, sync.offset, 1e-9) // the first, shortest trip rolled off: 9 and 9
        val sent = 2_000_000.0
        val boardUs = ((sent - 5_000.0) * 1_000).toLong()
        sync.add(sent, sent + 10.0, boardUs, boardUs) // the phone's clock set 5 s on
        assertFalse(sync.read)
    }

    @Test
    fun aBluetoothBoardIsReadWithTheRadioClockAndPingedFaster() {
        val radio = BoardBridge(radio = true)
        assertTrue(radio.sync is RadioSync)
        assertEquals(RADIO_PING_EVERY_MS, radio.pingEveryMs)
        assertTrue(BoardBridge().sync is ClockSync)
        assertEquals(PING_EVERY_MS, BoardBridge().pingEveryMs)
    }

    @Test
    fun pingsAreNumberedOnAndUnansweredOnesMakeTheBoardSilent() {
        val bridge = BoardBridge()
        assertEquals("PING 1", bridge.ping(0L))
        repeat(80) { bridge.ping(it.toLong()) }
        assertEquals(82L, bridge.nextPing)
        assertEquals(BoardTrouble.Silent, bridge.trouble(SILENT_MS + 80)) // unanswered: still said
    }

    @Test
    fun aBoardThatAnswersNoPingForSixSecondsIsSilent() {
        val bridge = connected()
        bridge.ping(0L)
        assertNull(bridge.trouble(SILENT_MS))
        assertEquals(BoardTrouble.Silent, bridge.trouble(SILENT_MS + 1))
        bridge.ping(5_000L)
        bridge.fromBoard("PONG 2 10 10", 5_003L) // a later one answered: the board is there
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
            listOf(SourceEvent.Board(BoardTrouble.OtherFirmware("0.3", BOARD_FIRMWARE))),
            bridge.fromBoard("HELLO beam-rp2040 0.3 e6614103", 0L),
        )
        assertEquals(1, bridge.read().fromBoard("BREAK 15 1000", 0L).size) // no count: from it on
        assertTrue(bridge.outbox.isEmpty())
        assertTrue(BoardBridge().fromBoard(HELLO, 0L).isEmpty()) // the same: nothing to say
    }

    @Test
    fun theEsp32c3IsABoardTooAndAHelloShortOfWordsIsTaken() {
        assertTrue(BoardBridge().fromBoard("HELLO beam-esp32c3 $BOARD_FIRMWARE 50543165e661 0", 0L).isEmpty())
        val bridge = BoardBridge()
        assertEquals(
            listOf(SourceEvent.Board(BoardTrouble.OtherFirmware("?", BOARD_FIRMWARE))),
            bridge.fromBoard("HELLO beam-rp2040", 0L),
        )
        assertEquals("beam-rp2040", bridge.board)
    }

    @Test
    fun aBreakBeforeTheHelloWaitsForTheBoardsClockNotDropped() {
        val bridge = BoardBridge()
        assertTrue(bridge.fromBoard("BREAK 1 2500000", 5L).isEmpty())
        assertTrue(bridge.fromBoard("HELLO another-board 1", 5L).isEmpty())
        assertTrue(bridge.fromBoard(hello(1), 6L).isEmpty())
        val n = bridge.ping(1_000_000L).split(" ")[1]
        assertEquals(
            listOf(SourceEvent.BeamBreak(1, 1_000_500L, "2500000")),
            bridge.fromBoard("PONG $n 2000000 2000000", 1_000_000L),
        )
        assertTrue(bridge.outbox.isEmpty()) // it came: nothing to ask for again
    }

    @Test
    fun aBoardThatNeverSaysHelloHasNoBreakCounted() {
        val bridge = BoardBridge()
        bridge.fromBoard("BREAK 1 1000", 0L)
        bridge.fromBoard("PONG 1 1000 1000", 0L)
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
    fun overBluetoothABreakWaitsLongerForTheClock() {
        val bridge = connected(radio = true)
        assertTrue(bridge.fromBoard("BREAK 1 812345", 0L).isEmpty())
        assertTrue(bridge.release(HELD_MS + 1).isEmpty())
        assertEquals(2, bridge.release(RADIO_HELD_MS + 1).size)
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
    fun breaksTheBoardCountedThatNeverCameAreAskedForAgain() {
        val bridge = connected(line = hello(3)).read()
        assertEquals(4L, (bridge.fromBoard("BREAK 4 1000", 0L).single() as SourceEvent.BeamBreak).seq)
        assertTrue(bridge.outbox.isEmpty())
        assertEquals(1, bridge.fromBoard("BREAK 7 4000", 10L).size)
        assertEquals(listOf("RESEND 5"), bridge.outbox)
        assertEquals(1, bridge.fromBoard("BREAK 5 2000", 20L).size) // said again
        assertEquals(1, bridge.fromBoard("BREAK 6 3000", 20L).size)
        assertTrue(bridge.fromBoard("BREAK 7 4000", 20L).isEmpty()) // had already
        assertTrue(bridge.release(1_000_000_000L).isEmpty()) // all came: nothing to say
    }

    @Test
    fun breaksAskedForAgainThatNeverComeAreSaidOnce() {
        val bridge = connected().read()
        bridge.fromBoard("BREAK 1 1000", 0L)
        bridge.fromBoard("BREAK 4 4000", 100L)
        assertTrue(bridge.release(100L + RESENT_WITHIN_MS).isEmpty())
        assertEquals(
            listOf(SourceEvent.Board(BoardTrouble.BreaksMissing(2, 3))),
            bridge.release(100L + RESENT_WITHIN_MS + 1),
        )
        assertTrue(bridge.release(Long.MAX_VALUE).isEmpty()) // said once
        assertEquals(1, bridge.fromBoard("BREAK 3 3000", 0L).size) // late: taken
    }

    @Test
    fun aBoardStartedAgainCountsAnew() {
        val bridge = connected().read()
        for (seq in 1..3) bridge.fromBoard("BREAK $seq ${seq}000", 0L)
        assertEquals(1, bridge.fromBoard("BREAK 1 500", 0L).size) // a lower count, not asked for
        assertEquals(1, bridge.fromBoard("BREAK 2 900", 0L).size)
        assertEquals(1, bridge.fromBoard("BREAK 2 950", 0L).size) // the same count, another time
        assertEquals(listOf("RESEND 1"), bridge.outbox) // count 1 since that start never came
    }

    @Test
    fun aBoardFoundAgainIsAskedForWhatItCountedMeanwhile() {
        val counts = mutableMapOf<String, Counts>()
        val first = BoardBridge(counts = counts).apply { fromBoard(HELLO, 0L) }.read()
        first.fromBoard("BREAK 1 1000", 0L)
        first.fromBoard("BREAK 2 2000", 0L)
        val later = BoardBridge(counts = counts).apply { fromBoard(hello(3), 50L) }
        assertEquals(listOf("RESEND 3"), later.outbox)
        val again = BoardBridge(counts = counts).apply { fromBoard(hello(1), 60L) } // below: started again
        assertEquals(listOf("RESEND 1"), again.outbox)
        val onceMore = BoardBridge(counts = counts).apply { fromBoard(hello(0), 70L) }
        assertTrue(onceMore.outbox.isEmpty())
    }

    @Test
    fun aBoardErrorIsSaidAndItsOtherLinesAreItsOwnBusiness() {
        val bridge = connected()
        assertEquals(listOf(SourceEvent.Error("board: unknown: XYZ")), bridge.fromBoard("ERROR unknown: XYZ", 0L))
        for (line in listOf("STATUS beam", "FLASH 1 5", "HOLDOFF 300", "", "BREAK x 1", ">>> PING 3", "PONG 9 1")) {
            assertTrue(line, bridge.fromBoard(line, 0L).isEmpty())
        }
    }

    @Test
    fun onlyALineWithItsEndCountsTimedWhenItsFirstPieceCame() {
        val splitter = LineSplitter()
        val first = splitter.feed("HELLO beam-rp2040\r\nBREAK 5 12".toByteArray(), 5L)
        assertEquals(listOf("HELLO beam-rp2040" to 5L), first)
        assertEquals(listOf("BREAK 5 12345678" to 5L), splitter.feed("345678\r\n".toByteArray(), 9L))
        assertTrue(LineSplitter().feed("BREAK 5 12".toByteArray(), 0L).isEmpty()) // cut short: no line
    }

    @Test
    fun overBluetoothALineIsTimedWhenItsFirstPieceCame() {
        val splitter = LineSplitter()
        assertTrue(splitter.feed("PONG 1 1234567890 12".toByteArray(), 10L).isEmpty())
        assertEquals(
            listOf("PONG 1 1234567890 1234567890" to 10L),
            splitter.feed("34567890\r\nBREAK 2 ".toByteArray(), 20L),
        )
        assertEquals(listOf("BREAK 2 99" to 20L), splitter.feed("99\r\n".toByteArray(), 30L))
    }

    @Test
    fun aHelloAskedAgainIsSaidOnceAndItsCountAsksForALostBreak() {
        val old = "HELLO beam-rp2040 0.3 e6614103 0"
        val bridge = BoardBridge()
        assertEquals(1, bridge.fromBoard(old, 0L).size) // the firmware said
        assertTrue(bridge.read().fromBoard(old, 5L).isEmpty()) // asked again: said once
        bridge.fromBoard("BREAK 1 1000", 6L)
        bridge.fromBoard(hello(2), 10L) // break 2's line lost: no break after it shows it
        assertEquals(listOf("RESEND 2"), bridge.outbox)
    }

    @Test
    fun aBoardStartedAgainUnderALinkThatStayedUpHasItsClockReadAnew() {
        val bridge = connected(radio = true).read()
        bridge.fromBoard("BREAK 1 1000", 0L)
        assertTrue(bridge.sync.read)
        bridge.fromBoard(HELLO, 10L) // its start's HELLO: count 0, below 1
        assertFalse(bridge.sync.read)
        assertTrue(bridge.fromBoard("BREAK 1 2000", 11L).isEmpty()) // waits for the new clock
    }
}
