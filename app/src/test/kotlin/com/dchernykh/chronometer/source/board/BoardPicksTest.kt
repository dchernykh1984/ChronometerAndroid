package com.dchernykh.chronometer.source.board

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardPicksTest {
    private val usb = BoardPick(BoardLink.USB, USB_KEY, "beam-rp2040")
    private val near = BoardPick(BoardLink.BLE, "AA:BB", "beam-7c1c")
    private val gone = BoardPick(BoardLink.BLE, "CC:DD", "beam-b6b0")

    @Test
    fun thePicksAreKeptAsOneSettingAndReadBack() {
        assertEquals(listOf(usb, near), decodePicks(encodePicks(listOf(usb, near))))
        assertEquals(emptyList<BoardPick>(), decodePicks(""))
        // A line that is not a pick is left out: of another link, short, or with no key.
        assertEquals(listOf(near), decodePicks("WIFI\tx\ty\nBLE\tAA:BB\tbeam-7c1c\nBLE\tonly\nUSB\t \tusb"))
    }

    @Test
    fun theListHasTheBoardsFoundThenThosePickedAndNotFound() {
        val found = listOf(BoardFound(usb), BoardFound(near, rssi = -61))
        assertEquals(
            listOf(
                BoardRow(BoardFound(usb), picked = false, here = true),
                BoardRow(BoardFound(near, rssi = -61), picked = true, here = true),
                BoardRow(BoardFound(gone), picked = true, here = false),
            ),
            boardRows(found, listOf(near, gone)),
        )
    }

    @Test
    fun aBoardPickedByItsNameBeforeIsTheBoardFoundOfThatNameListedOnce() {
        val byName = BoardPick(BoardLink.BLE, "BEAM-7C1C", "BEAM-7C1C") // the Bluetooth beam's typed name
        assertEquals(
            listOf(BoardRow(BoardFound(near, rssi = -61), picked = true, here = true)),
            boardRows(listOf(BoardFound(near, rssi = -61)), listOf(byName)),
        )
        assertTrue(byName.isBoard(near))
        assertFalse(byName.isBoard(usb))
        assertFalse(BoardPick(BoardLink.USB, "beam-7c1c", "x").isBoard(near)) // a name is the radio's
    }
}
