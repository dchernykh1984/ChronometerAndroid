package com.dchernykh.chronometer.source

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
    fun onlyTheTestDevicesRunInThisRound() {
        assertEquals(
            listOf(DeviceId.TEST_BEAM, DeviceId.TEST_CHIPS),
            DeviceId.entries.filter { it.available },
        )
        assertEquals(DeviceId.entries.filter { it.available }, DeviceId.entries.filter { it.test })
    }

    @Test
    fun anUnknownOrForeignNameFallsBackToTheKindsTestDevice() {
        assertEquals(DeviceId.LLRP, DeviceId.fromName("LLRP", SourceKind.CHIPS))
        assertEquals(DeviceId.TEST_BEAM, DeviceId.fromName("LLRP", SourceKind.BEAM))
        assertEquals(DeviceId.TEST_CHIPS, DeviceId.fromName(null, SourceKind.CHIPS))
        assertEquals(SourceKind.BEAM, SourceKind.fromName("BEAM"))
        assertEquals(SourceKind.HAND, SourceKind.fromName("laser"))
    }
}
