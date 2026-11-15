package com.dchernykh.chronometer.source.board

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.PowerManager
import com.dchernykh.chronometer.source.OpenFailure
import java.io.Closeable
import java.io.IOException

/** MicroPython on an RP2040: the beam board's USB ids (res/xml/beam_board.xml says the same). */
const val BOARD_VENDOR = 0x2E8A
const val BOARD_PRODUCT = 0x0005

/** The ESP32-C3's own USB (its USB Serial/JTAG), which MicroPython there speaks over. */
const val ESP_VENDOR = 0x303A
const val ESP_PRODUCT = 0x1001

private const val ASKED = "com.dchernykh.chronometer.BEAM_BOARD_ASKED"

// Longer than any race day; the lock goes when the USB beam stops.
private const val AWAKE_MS = 24 * 60 * 60 * 1_000L

// CDC's SET_CONTROL_LINE_STATE, DTR and RTS on: MicroPython takes a port without DTR for
// one no program has open.
private const val CLASS_TO_INTERFACE = 0x21
private const val SET_CONTROL_LINE_STATE = 0x22
private const val DTR_AND_RTS = 0x03

// The ESP32-C3's own USB holds the chip in reset while RTS is on, and starts its bootloader
// when RTS goes off with DTR on: both stay off, as its MicroPython needs neither.
private const val LINES_OFF = 0x00

/** Each beam board's USB ids, and the control lines its port is opened with. */
private val BOARDS = mapOf((BOARD_VENDOR to BOARD_PRODUCT) to DTR_AND_RTS, (ESP_VENDOR to ESP_PRODUCT) to LINES_OFF)
private const val CONTROL_MS = 1_000

/** The beam boards on the phone's USB, found by their ids, the first one taken. */
class UsbBoardPorts(
    private val context: Context,
) : BoardPorts {
    private val usb: UsbManager? = context.getSystemService(UsbManager::class.java)

    override fun find(): FoundBoard? {
        val usb = usb ?: return null
        val device =
            usb.deviceList.values
                .sortedBy { it.deviceName }
                .firstOrNull { (it.vendorId to it.productId) in BOARDS }
                ?: return null
        val lines = BOARDS.getValue(device.vendorId to device.productId)
        return FoundBoard(
            permitted = usb.hasPermission(device),
            ask = { ask(usb, device) },
            open = { UsbBoardPort.open(usb, device, lines) },
        )
    }

    override fun keepAwake(): Closeable {
        val lock =
            context
                .getSystemService(PowerManager::class.java)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "chronometer:usb-beam")
                ?.apply {
                    setReferenceCounted(false)
                    acquire(AWAKE_MS)
                }
        return Closeable { lock?.takeIf { it.isHeld }?.release() }
    }

    // Android's question to the referee; its answer is read by hasPermission, not here.
    private fun ask(
        usb: UsbManager,
        device: UsbDevice,
    ) {
        val asked = Intent(ASKED).setPackage(context.packageName)
        usb.requestPermission(device, PendingIntent.getBroadcast(context, 0, asked, PendingIntent.FLAG_IMMUTABLE))
    }
}

/** A beam board's CDC serial port, its data interface claimed for this app alone. */
class UsbBoardPort private constructor(
    private val usb: UsbManager,
    private val device: UsbDevice,
    private val connection: UsbDeviceConnection,
    private val claimed: List<UsbInterface>,
    private val input: UsbEndpoint,
    private val output: UsbEndpoint,
) : BoardPort {
    private val buffer = ByteArray(maxOf(input.maxPacketSize, 64) * 4)

    override fun read(timeoutMs: Int): ByteArray {
        val got = connection.bulkTransfer(input, buffer, buffer.size, timeoutMs)
        if (got > 0) return buffer.copyOf(got)
        // -1 is a timeout and a board gone alike: only the device list tells them apart.
        if (device.deviceName !in usb.deviceList) throw IOException("the beam board was unplugged")
        return ByteArray(0)
    }

    override fun write(
        bytes: ByteArray,
        timeoutMs: Int,
    ) {
        if (connection.bulkTransfer(output, bytes, bytes.size, timeoutMs) < bytes.size) {
            throw IOException("the beam board took no line")
        }
    }

    override fun close() {
        claimed.forEach(connection::releaseInterface)
        connection.close()
    }

    companion object {
        /** Open [device]'s serial port, its data and control interfaces claimed. */
        fun open(
            usb: UsbManager,
            device: UsbDevice,
            lines: Int = DTR_AND_RTS,
        ): UsbBoardPort {
            val interfaces = (0 until device.interfaceCount).map(device::getInterface)
            val control = interfaces.firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_COMM }
            val data =
                interfaces.firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_CDC_DATA }
                    ?: notOpened(OpenFailure.NO_PORT)
            val bulk =
                (0 until data.endpointCount)
                    .map(data::getEndpoint)
                    .filter { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK }
            val input = bulk.firstOrNull { it.direction == UsbConstants.USB_DIR_IN }
            val output = bulk.firstOrNull { it.direction == UsbConstants.USB_DIR_OUT }
            if (input == null || output == null) notOpened(OpenFailure.NO_PORT)
            val connection = usb.openDevice(device) ?: notOpened(OpenFailure.REFUSED)
            val claimed = listOfNotNull(data, control)
            if (!claimed.all { connection.claimInterface(it, true) }) {
                connection.close()
                notOpened(OpenFailure.HELD)
            }
            connection.controlTransfer(
                CLASS_TO_INTERFACE,
                SET_CONTROL_LINE_STATE,
                lines,
                control?.id ?: 0,
                null,
                0,
                CONTROL_MS,
            )
            return UsbBoardPort(usb, device, connection, claimed, input, output)
        }
    }
}

private fun notOpened(why: OpenFailure): Nothing = throw BoardNotOpened(why)
