package com.dchernykh.chronometer.source

import com.dchernykh.chronometer.source.board.BoardBeamSource
import com.dchernykh.chronometer.source.board.BoardPorts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DevicesTest {
    @Test
    fun eachKindListsItsTestDeviceFirstAndItsStubsAfter() {
        assertEquals(
            listOf(DeviceId.TEST_BEAM, DeviceId.USB_BEAM, DeviceId.BLE_BEAM),
            DeviceId.of(SourceKind.BEAM),
        )
        assertEquals(
            listOf(DeviceId.TEST_CHIPS, DeviceId.LLRP, DeviceId.RACERESULT, DeviceId.R2000),
            DeviceId.of(SourceKind.CHIPS),
        )
        assertTrue(DeviceId.of(SourceKind.HAND).isEmpty())
    }

    @Test
    fun theTestDevicesAndBothBeamsRunTheOthersAreStubs() {
        assertEquals(
            listOf(DeviceId.TEST_BEAM, DeviceId.USB_BEAM, DeviceId.BLE_BEAM, DeviceId.TEST_CHIPS),
            DeviceId.entries.filter { it.available },
        )
        assertEquals(listOf(DeviceId.TEST_BEAM, DeviceId.TEST_CHIPS), DeviceId.entries.filter { it.test })
    }

    @Test
    fun anUnknownOrForeignNameFallsBackToTheKindsTestDevice() {
        assertEquals(DeviceId.LLRP, DeviceId.fromName("LLRP", SourceKind.CHIPS))
        assertEquals(DeviceId.TEST_BEAM, DeviceId.fromName("LLRP", SourceKind.BEAM))
        assertEquals(DeviceId.TEST_CHIPS, DeviceId.fromName(null, SourceKind.CHIPS))
        assertEquals(SourceKind.BEAM, SourceKind.fromName("BEAM"))
        assertEquals(SourceKind.HAND, SourceKind.fromName("laser"))
    }

    @Test
    fun eachBeamRunsOnItsOwnLinkAndNoneWithoutOne() {
        val ports = BoardPorts { null }
        val chips = {
            com.dchernykh.chronometer.chips
                .ChipMap()
        }
        assertTrue(builtInSource(DeviceId.USB_BEAM, chips, boards = { ports }) is BoardBeamSource)
        assertTrue(builtInSource(DeviceId.BLE_BEAM, chips, radios = { ports }) is BoardBeamSource)
        assertEquals(null, builtInSource(DeviceId.BLE_BEAM, chips))
        assertEquals(null, builtInSource(DeviceId.LLRP, chips))
    }
}
