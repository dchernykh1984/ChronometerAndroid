package com.dchernykh.chronometer.source

import com.dchernykh.chronometer.source.board.BoardsSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DevicesTest {
    @Test
    fun theBoardsComeFirstThenTheTestDevicesThenTheStubs() {
        assertEquals(listOf(DeviceId.BOARDS, DeviceId.TEST_BEAM), DeviceId.of(SourceKind.BEAM))
        assertEquals(
            listOf(DeviceId.TEST_CHIPS, DeviceId.LLRP, DeviceId.RACERESULT, DeviceId.R2000),
            DeviceId.of(SourceKind.CHIPS),
        )
        assertTrue(DeviceId.of(SourceKind.HAND).isEmpty())
    }

    @Test
    fun theBoardsAndTheTestDevicesRunTheOthersAreStubs() {
        assertEquals(
            listOf(DeviceId.BOARDS, DeviceId.TEST_BEAM, DeviceId.TEST_CHIPS),
            DeviceId.entries.filter { it.available },
        )
        assertEquals(listOf(DeviceId.TEST_BEAM, DeviceId.TEST_CHIPS), DeviceId.entries.filter { it.test })
    }

    @Test
    fun anUnknownOrForeignNameFallsBackToTheKindsFirstDevice() {
        assertEquals(DeviceId.LLRP, DeviceId.fromName("LLRP", SourceKind.CHIPS))
        assertEquals(DeviceId.BOARDS, DeviceId.fromName("LLRP", SourceKind.BEAM))
        // The USB and Bluetooth beams of before spec 003: the beam boards now.
        assertEquals(DeviceId.BOARDS, DeviceId.fromName("BLE_BEAM", SourceKind.BEAM))
        assertEquals(DeviceId.TEST_CHIPS, DeviceId.fromName(null, SourceKind.CHIPS))
        assertEquals(SourceKind.BEAM, SourceKind.fromName("BEAM"))
        assertEquals(SourceKind.HAND, SourceKind.fromName("laser"))
    }

    @Test
    fun theBoardsRunAsTheAppMakesThemAndNoneWithout() {
        val boards = BoardsSource(emptyList()) { _, _ -> error("no board picked") }
        val chips = {
            com.dchernykh.chronometer.chips
                .ChipMap()
        }
        assertTrue(builtInSource(DeviceId.BOARDS, chips, boards = { boards }) === boards)
        assertEquals(null, builtInSource(DeviceId.BOARDS, chips))
        assertEquals(null, builtInSource(DeviceId.LLRP, chips))
    }
}
