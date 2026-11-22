package com.dchernykh.chronometer.source.board

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.hardware.usb.UsbManager
import com.dchernykh.chronometer.source.BoardTrouble
import kotlinx.coroutines.delay
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** The kinds of board on USB, by their ids: the name each one's HELLO gives. */
private val USB_KINDS =
    mapOf(
        (BOARD_VENDOR to BOARD_PRODUCT) to "beam-rp2040",
        (ESP_VENDOR to ESP_PRODUCT) to "beam-esp32c3",
    )

/** What a search found, and why it could not look on Bluetooth, if it could not. */
data class BoardSearch(
    val found: List<BoardFound>,
    val trouble: BoardTrouble? = null,
)

/** Whether a device seen on Bluetooth is a beam board: named so, or offering the serial service (a BT05's name). */
fun isBoard(
    name: String?,
    services: List<java.util.UUID>,
): Boolean = name?.startsWith("beam-") == true || SERIAL_SERVICE in services

/**
 * The beam boards around (ChronometerPython's spec 003, FR-001): the one on the phone's USB, by
 * its ids, then those on Bluetooth, 5 s of a scan, the nearest first.
 */
class BoardFinder(
    private val context: Context,
    private val scanMs: Long = SCAN_MS,
    private val gate: ScanGate = ScanGate.app,
) {
    fun find(): BoardSearch {
        val usb =
            context
                .getSystemService(UsbManager::class.java)
                ?.deviceList
                ?.values
                ?.sortedBy { it.deviceName }
                ?.firstNotNullOfOrNull { USB_KINDS[it.vendorId to it.productId] }
                ?.let { listOf(BoardFound(BoardPick(BoardLink.USB, USB_KEY, it))) }
                .orEmpty()
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        return when {
            !BluetoothLeave.granted(context) -> {
                BluetoothLeave.ask()
                BoardSearch(usb, BoardTrouble.NoBluetoothLeave)
            }
            adapter == null || !adapter.isEnabled -> BoardSearch(usb, BoardTrouble.BluetoothOff)
            else -> BoardSearch(usb + scan(adapter.bluetoothLeScanner))
        }
    }

    // The leave is checked in find() before this runs.
    @SuppressLint("MissingPermission")
    private fun scan(scanner: android.bluetooth.le.BluetoothLeScanner?): List<BoardFound> {
        scanner ?: return emptyList()
        gate.take()
        val seen = ConcurrentHashMap<String, BoardFound>()
        val callback =
            object : ScanCallback() {
                override fun onScanResult(
                    callbackType: Int,
                    result: ScanResult,
                ) {
                    // What a device around advertises is anyone's: no control character kept.
                    val name = (result.scanRecord?.deviceName ?: result.device.name)?.let(::plain)
                    val services =
                        result.scanRecord
                            ?.serviceUuids
                            .orEmpty()
                            .map { it.uuid }
                    if (isBoard(name, services)) {
                        val address = result.device.address
                        seen[address] = BoardFound(BoardPick(BoardLink.BLE, address, name ?: address), result.rssi)
                    }
                }
            }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(null, settings, callback)
        try {
            Thread.sleep(scanMs)
        } finally {
            scanner.stopScan(callback)
        }
        return seen.values.sortedByDescending { it.rssi }
    }
}

/** How a blink went: by the source reading the board, or by [blinkOnce]. */
enum class Blinked {
    REACHING,
    DONE,
    NOT_CONNECTED,
    NOT_FOUND,
    NOT_PERMITTED,
    NOT_OPENED,
}

/** The last blink asked for, for the screen: the board's name, and how it went. */
data class BlinkNote(
    val name: String,
    val blinked: Blinked,
)

/** The search as the screen shows it: running, and what the last one found. */
data class BoardSearchState(
    val running: Boolean = false,
    val search: BoardSearch? = null,
)

/**
 * A board read by no source blinked: found, opened for a moment, FLASH 1 to FLASH 5 sent and let
 * go (FR-003). Blocking: off the main thread.
 */
suspend fun blinkOnce(ports: BoardPorts): Blinked {
    val found = ports.find()
    return when {
        found == null -> Blinked.NOT_FOUND
        !found.permitted -> {
            found.ask()
            Blinked.NOT_PERMITTED
        }
        // Whatever opening throws, the board is not opened.
        else -> runCatching { found.open() }.getOrNull()?.let { flash(it) } ?: Blinked.NOT_OPENED
    }
}

private suspend fun flash(port: BoardPort): Blinked =
    try {
        for (n in 1..FLASHES) {
            port.write("FLASH $n\n".toByteArray(), FLASH_EVERY_MS.toInt())
            delay(FLASH_EVERY_MS)
        }
        Blinked.DONE
    } catch (_: IOException) {
        Blinked.NOT_OPENED
    } finally {
        port.close()
    }
