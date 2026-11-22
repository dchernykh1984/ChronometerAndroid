package com.dchernykh.chronometer.source.board

import android.bluetooth.BluetoothManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.dchernykh.chronometer.source.BoardTrouble
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadow.api.Shadow
import org.robolectric.util.ReflectionHelpers
import java.io.IOException
import java.util.UUID

/** A board to blink: found or not, the app let use it or not, opened or not. */
private class Blinkable(
    val found: Boolean = true,
    val permitted: Boolean = true,
    val opens: Boolean = true,
    val takes: Boolean = true,
) : BoardPorts {
    val written = mutableListOf<String>()
    var asked = 0
    var closed = false

    override fun find(): FoundBoard? =
        if (!found) {
            null
        } else {
            FoundBoard(permitted = permitted, ask = { asked++ }, open = {
                if (!opens) throw BoardNotOpened(com.dchernykh.chronometer.source.OpenFailure.HELD)
                object : BoardPort {
                    override fun read(timeoutMs: Int) = ByteArray(0)

                    override fun write(
                        bytes: ByteArray,
                        timeoutMs: Int,
                    ) {
                        if (!takes) throw IOException("the board took no write")
                        written += String(bytes).trim()
                    }

                    override fun close() {
                        closed = true
                    }
                }
            })
        }
}

@RunWith(RobolectricTestRunner::class)
class BoardFinderTest {
    private val app = RuntimeEnvironment.getApplication()

    @Test
    fun aBoardOnTheRadioIsNamedSoOrOffersTheSerialService() {
        assertTrue(isBoard("beam-7c1c", emptyList()))
        assertTrue(isBoard("BT05", listOf(SERIAL_SERVICE))) // a BT05 that kept its own name
        assertFalse(isBoard("Headphones", listOf(UUID.randomUUID())))
        assertFalse(isBoard(null, emptyList()))
    }

    @Test
    fun aBoardReadByNoSourceIsReachedBlinkedAndLetGo() =
        runTest {
            val board = Blinkable()
            assertEquals(Blinked.DONE, blinkOnce(board))
            assertEquals((1..FLASHES).map { "FLASH $it" }, board.written)
            assertTrue(board.closed)
            assertEquals(Blinked.NOT_FOUND, blinkOnce(Blinkable(found = false)))
            val refused = Blinkable(permitted = false)
            assertEquals(Blinked.NOT_PERMITTED, blinkOnce(refused))
            assertEquals(1, refused.asked)
            assertEquals(Blinked.NOT_OPENED, blinkOnce(Blinkable(opens = false)))
            val deaf = Blinkable(takes = false)
            assertEquals(Blinked.NOT_OPENED, blinkOnce(deaf))
            assertTrue(deaf.closed)
        }

    @Test
    fun theBoardOnUsbIsFoundAndBluetoothsTroubleSaid() {
        val device = Shadow.newInstanceOf(UsbDevice::class.java)
        ReflectionHelpers.setField(device, "mName", "/dev/bus/usb/001/007")
        ReflectionHelpers.setField(device, "mVendorId", ESP_VENDOR)
        ReflectionHelpers.setField(device, "mProductId", ESP_PRODUCT)
        shadowOf(app.getSystemService(UsbManager::class.java)).addOrUpdateUsbDevice(device, true)
        val usb = BoardFound(BoardPick(BoardLink.USB, USB_KEY, "beam-esp32c3"))
        shadowOf(app).denyPermissions(*BluetoothLeave.needed)
        assertEquals(BoardSearch(listOf(usb), BoardTrouble.NoBluetoothLeave), BoardFinder(app, scanMs = 10).find())
        shadowOf(app).grantPermissions(*BluetoothLeave.needed)
        val adapter = app.getSystemService(BluetoothManager::class.java).adapter
        shadowOf(adapter).setEnabled(false)
        assertEquals(BoardSearch(listOf(usb), BoardTrouble.BluetoothOff), BoardFinder(app, scanMs = 10).find())
        shadowOf(adapter).setEnabled(true)
        assertEquals(BoardSearch(listOf(usb)), BoardFinder(app, scanMs = 10, gate = ScanGate({ 0L }, {})).find())
    }
}
