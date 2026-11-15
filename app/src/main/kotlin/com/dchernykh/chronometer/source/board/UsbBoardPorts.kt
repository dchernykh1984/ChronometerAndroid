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
import java.io.IOException

/** MicroPython on an RP2040: the beam board's USB ids (res/xml/beam_board.xml says the same). */
const val BOARD_VENDOR = 0x2E8A
const val BOARD_PRODUCT = 0x0005

private const val ASKED = "com.dchernykh.chronometer.BEAM_BOARD_ASKED"

// CDC's SET_CONTROL_LINE_STATE, DTR and RTS on: MicroPython takes a port without DTR for
// one no program has open.
private const val CLASS_TO_INTERFACE = 0x21
private const val SET_CONTROL_LINE_STATE = 0x22
private const val DTR_AND_RTS = 0x03
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
                .firstOrNull { it.vendorId == BOARD_VENDOR && it.productId == BOARD_PRODUCT }
                ?: return null
        return FoundBoard(
            permitted = usb.hasPermission(device),
            ask = { ask(usb, device) },
            open = { UsbBoardPort.open(usb, device) },
        )
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
        ): UsbBoardPort {
            val interfaces = (0 until device.interfaceCount).map(device::getInterface)
            val control = interfaces.firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_COMM }
            val data =
                interfaces.firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_CDC_DATA }
                    ?: notOpened("the beam board shows no serial port")
            val bulk =
                (0 until data.endpointCount)
                    .map(data::getEndpoint)
                    .filter { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK }
            val input = bulk.firstOrNull { it.direction == UsbConstants.USB_DIR_IN }
            val output = bulk.firstOrNull { it.direction == UsbConstants.USB_DIR_OUT }
            if (input == null || output == null) notOpened("the beam board's serial port has no line in or out")
            val connection = usb.openDevice(device) ?: notOpened("Android would not open the beam board")
            val claimed = listOfNotNull(data, control)
            if (!claimed.all { connection.claimInterface(it, true) }) {
                connection.close()
                notOpened("another app holds the beam board")
            }
            connection.controlTransfer(
                CLASS_TO_INTERFACE,
                SET_CONTROL_LINE_STATE,
                DTR_AND_RTS,
                control?.id ?: 0,
                null,
                0,
                CONTROL_MS,
            )
            return UsbBoardPort(usb, device, connection, claimed, input, output)
        }
    }
}

private fun notOpened(why: String): Nothing = throw IOException(why)
