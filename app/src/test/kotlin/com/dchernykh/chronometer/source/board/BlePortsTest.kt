package com.dchernykh.chronometer.source.board

import android.bluetooth.BluetoothGattCharacteristic.PROPERTY_NOTIFY
import android.bluetooth.BluetoothGattCharacteristic.PROPERTY_READ
import android.bluetooth.BluetoothGattCharacteristic.PROPERTY_WRITE
import android.bluetooth.BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
import android.bluetooth.BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
import android.bluetooth.BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
import android.bluetooth.BluetoothManager
import com.dchernykh.chronometer.source.BoardTrouble
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowPowerManager

/** The phone's Bluetooth as Robolectric fakes it: the board named, the leave, the radio off. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class BlePortsTest {
    private val app = RuntimeEnvironment.getApplication()

    @Test
    fun aBoardTakingOnlyWritesWithResponseIsWrittenSo() {
        val bt05 = PROPERTY_READ or PROPERTY_WRITE or PROPERTY_NOTIFY // the prototype's MLT-BT05
        assertEquals(WRITE_TYPE_DEFAULT, writeTypeFor(bt05))
        assertEquals(WRITE_TYPE_NO_RESPONSE, writeTypeFor(bt05 or PROPERTY_WRITE_NO_RESPONSE))
    }

    @Test
    fun aBoardIsTheOneNamedByItsNameOrItsAddressAndNoOther() {
        assertTrue(isNamed("beam-4103", "BEAM-4103", "AA:BB:CC:DD:EE:FF"))
        assertTrue(isNamed("aa:bb:cc:dd:ee:ff", null, "AA:BB:CC:DD:EE:FF"))
        assertFalse(isNamed("beam-4103", "beam-9999", "AA:BB:CC:DD:EE:FF"))
        assertFalse(isNamed("beam-4103", null, "AA:BB:CC:DD:EE:FF"))
    }

    @Test
    fun noBoardNamedIsNoneReadAndTheScreenSaysToNameOne() {
        val ports = BlePorts(app, "  ")
        assertNull(ports.find())
        assertEquals(BoardTrouble.NotNamed, ports.missing())
    }

    @Test
    fun withoutTheLeaveTheAppIsAskedForItAndOpensNothing() =
        runTest {
            shadowOf(app).denyPermissions(*BluetoothLeave.needed)
            val found = BlePorts(app, "beam-4103").find()!!
            assertFalse(found.permitted)
            assertEquals(BoardTrouble.NoBluetoothLeave, found.refused)
            val asked = async { BluetoothLeave.asks.first() }
            runCurrent()
            found.ask()
            assertEquals(Unit, asked.await())
            assertThrows(BoardNotOpened::class.java) { found.open() }
        }

    @Test
    fun bluetoothOffIsSaidNotLookedFor() {
        shadowOf(app).grantPermissions(*BluetoothLeave.needed)
        val adapter = app.getSystemService(BluetoothManager::class.java).adapter
        shadowOf(adapter).setEnabled(false)
        val ports = BlePorts(app, "beam-4103")
        assertNull(ports.find())
        assertEquals(BoardTrouble.BluetoothOff, ports.missing())
    }

    @Test
    fun theProcessorIsKeptAwakeUntilTheBluetoothBeamLetsGo() {
        val awake = BlePorts(app, "beam-4103").keepAwake()
        val lock = ShadowPowerManager.getLatestWakeLock()
        assertTrue(lock.isHeld)
        awake.close()
        assertFalse(lock.isHeld)
    }

    @Test
    fun scansAreSpacedSoAndroidNeverStopsGivingResults() {
        shadowOf(app).grantPermissions(*BluetoothLeave.needed)
        shadowOf(app.getSystemService(BluetoothManager::class.java).adapter).setEnabled(true)
        var now = 0L
        val pauses = mutableListOf<Long>()
        val ports =
            BlePorts(app, "beam-4103", scanMs = 10, gate = ScanGate({ now }, { pauses += it }))
        assertNull(ports.find()) // the first scan, no wait; none of the name in range
        assertEquals(BoardTrouble.NotInRange("beam-4103"), ports.missing())
        now += 1_000
        ports.find()
        assertEquals(listOf(5_500L), pauses)
    }
}
