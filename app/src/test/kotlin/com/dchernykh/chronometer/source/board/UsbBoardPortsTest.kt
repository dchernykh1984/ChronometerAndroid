package com.dchernykh.chronometer.source.board

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowPowerManager
import org.robolectric.util.ReflectionHelpers

/** The phone's USB as Robolectric fakes it: the beam board found among other devices. */
@RunWith(RobolectricTestRunner::class)
class UsbBoardPortsTest {
    private val app = RuntimeEnvironment.getApplication()
    private val usb = app.getSystemService(UsbManager::class.java)

    private fun plugIn(
        name: String,
        vendor: Int,
        product: Int,
        permitted: Boolean,
    ) {
        val device = Shadow.newInstanceOf(UsbDevice::class.java)
        ReflectionHelpers.setField(device, "mName", name)
        ReflectionHelpers.setField(device, "mVendorId", vendor)
        ReflectionHelpers.setField(device, "mProductId", product)
        shadowOf(usb).addOrUpdateUsbDevice(device, permitted)
    }

    @Test
    fun noBoardAmongOtherDevicesIsNone() {
        plugIn("/dev/bus/usb/001/002", BOARD_VENDOR, 0x000C, permitted = true) // a Raspberry Pi Debug Probe
        plugIn("/dev/bus/usb/001/003", 0x0403, 0x6001, permitted = true) // a serial adapter
        assertNull(UsbBoardPorts(app).find())
    }

    @Test
    fun theBoardIsFoundByItsIdsWithWhetherTheAppMayUseIt() {
        plugIn("/dev/bus/usb/001/002", BOARD_VENDOR, 0x000C, permitted = true)
        plugIn("/dev/bus/usb/001/005", BOARD_VENDOR, BOARD_PRODUCT, permitted = false)
        val found = UsbBoardPorts(app).find()
        assertFalse(found!!.permitted)
        found.ask() // Android's question: Robolectric's USB takes it without a word
        plugIn("/dev/bus/usb/001/005", BOARD_VENDOR, BOARD_PRODUCT, permitted = true)
        assertTrue(UsbBoardPorts(app).find()!!.permitted)
    }

    @Test
    fun anEsp32c3OnItsOwnUsbIsABeamBoardToo() {
        plugIn("/dev/bus/usb/001/007", ESP_VENDOR, ESP_PRODUCT, permitted = true)
        assertTrue(UsbBoardPorts(app).find()!!.permitted)
    }

    @Test
    fun theProcessorIsKeptAwakeUntilTheUsbBeamLetsGo() {
        val awake = UsbBoardPorts(app).keepAwake()
        val lock = ShadowPowerManager.getLatestWakeLock()
        assertTrue(lock.isHeld)
        awake.close()
        assertFalse(lock.isHeld)
        awake.close() // a second close is no harm
    }
}
