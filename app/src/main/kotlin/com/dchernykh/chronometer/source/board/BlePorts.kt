package com.dchernykh.chronometer.source.board

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import com.dchernykh.chronometer.source.BoardTrouble
import com.dchernykh.chronometer.source.OpenFailure
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.Closeable
import java.io.IOException
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/** The HM-10's serial service: the RP2040's BT05 has it, the ESP32-C3's firmware offers it. */
val SERIAL_SERVICE: UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
val SERIAL_CHARACTERISTIC: UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
private val NOTIFICATIONS: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

private const val SCAN_MS = 5_000L
private const val CONNECT_MS = 10_000L
private const val CHUNK = 20 // a write's bytes at the default ATT MTU
private const val AWAKE_MS = 24 * 60 * 60 * 1_000L

/** Whether a board seen in a scan is the one named: by its name or its address. */
fun isNamed(
    wanted: String,
    name: String?,
    address: String,
): Boolean = wanted.equals(name, ignoreCase = true) || wanted.equals(address, ignoreCase = true)

/**
 * The leave Bluetooth needs: to scan and connect on Android 12 and later, the location
 * before it. The app's screen asks for it when [asks] says so.
 */
object BluetoothLeave {
    private val asking = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val asks: SharedFlow<Unit> = asking.asSharedFlow()

    val needed: Array<String>
        get() =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            }

    fun granted(context: Context): Boolean =
        needed.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    fun ask() {
        asking.tryEmit(Unit)
    }
}

/**
 * The beam board over Bluetooth Low Energy, the one named in the settings ([name]): the
 * app never reads another one in range (contract, step 1). [clock] times each notification
 * as it comes.
 */
class BlePorts(
    private val context: Context,
    private val name: String,
    private val clock: () -> Long = System::currentTimeMillis,
) : BoardPorts {
    private var why: BoardTrouble = BoardTrouble.NotFound

    override fun missing(): BoardTrouble = why

    override fun find(): FoundBoard? {
        val wanted = name.trim()
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        return when {
            wanted.isEmpty() -> none(BoardTrouble.NotNamed)
            !BluetoothLeave.granted(context) ->
                FoundBoard(
                    permitted = false,
                    ask = BluetoothLeave::ask,
                    open = { throw BoardNotOpened(OpenFailure.REFUSED) },
                    refused = BoardTrouble.NoBluetoothLeave,
                )
            adapter == null || !adapter.isEnabled -> none(BoardTrouble.BluetoothOff)
            else ->
                scan(adapter.bluetoothLeScanner, wanted)
                    ?.let { device ->
                        FoundBoard(permitted = true, ask = {}, open = { BlePort.open(context, device, clock) })
                    }
                    ?: none(BoardTrouble.NotInRange(wanted))
        }
    }

    override fun keepAwake(): Closeable {
        val lock =
            context
                .getSystemService(PowerManager::class.java)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "chronometer:ble-beam")
                ?.apply {
                    setReferenceCounted(false)
                    acquire(AWAKE_MS)
                }
        return Closeable { lock?.takeIf { it.isHeld }?.release() }
    }

    private fun none(trouble: BoardTrouble): FoundBoard? {
        why = trouble
        return null
    }

    // The leave is checked in find() before any of this runs.
    @SuppressLint("MissingPermission")
    private fun scan(
        scanner: android.bluetooth.le.BluetoothLeScanner?,
        wanted: String,
    ): BluetoothDevice? {
        scanner ?: return null
        val found = LinkedBlockingQueue<BluetoothDevice>()
        val callback =
            object : ScanCallback() {
                override fun onScanResult(
                    callbackType: Int,
                    result: ScanResult,
                ) {
                    val name = result.scanRecord?.deviceName ?: result.device.name
                    if (isNamed(wanted, name, result.device.address)) found.offer(result.device)
                }
            }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(null, settings, callback)
        return try {
            found.poll(SCAN_MS, TimeUnit.MILLISECONDS)
        } finally {
            scanner.stopScan(callback)
        }
    }
}

/**
 * What a board's link tells as Android says it, on its own threads: each step of opening
 * the link, the serial characteristic's notifications with the moment each came, each
 * write taken, and the link lost.
 */
private class GattEvents(
    private val clock: () -> Long,
) : BluetoothGattCallback() {
    val pieces = LinkedBlockingQueue<Pair<ByteArray, Long>>()
    val steps = LinkedBlockingQueue<Int>() // connected, discovered, subscribed: their status
    val written = Semaphore(0)

    @Volatile var gone = false

    fun awaitStep(): Boolean = steps.poll(CONNECT_MS, TimeUnit.MILLISECONDS) == BluetoothGatt.GATT_SUCCESS

    override fun onConnectionStateChange(
        gatt: BluetoothGatt,
        status: Int,
        newState: Int,
    ) {
        if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
            steps.offer(status)
        } else {
            gone = true
            steps.offer(-1)
            pieces.offer(ByteArray(0) to clock()) // the reader learns the link is lost
        }
    }

    override fun onServicesDiscovered(
        gatt: BluetoothGatt,
        status: Int,
    ) {
        steps.offer(status)
    }

    override fun onDescriptorWrite(
        gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        status: Int,
    ) {
        steps.offer(status)
    }

    override fun onCharacteristicWrite(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        status: Int,
    ) {
        written.release()
    }

    override fun onCharacteristicChanged(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
    ) {
        if (value.isNotEmpty()) pieces.offer(value.copyOf() to clock())
    }

    @Deprecated("Android 12 and before call this one")
    @Suppress("DEPRECATION")
    override fun onCharacteristicChanged(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
    ) {
        characteristic.value?.takeIf { it.isNotEmpty() }?.let { pieces.offer(it.copyOf() to clock()) }
    }
}

/**
 * A board connected over Bluetooth: its serial characteristic's notifications in, writes
 * of [CHUNK] bytes out, one at a time as Android allows. Each notification keeps the moment
 * it came, which the reader takes for a line's ([arrivedAt]).
 */
@SuppressLint("MissingPermission") // the leave is checked before a port is opened
class BlePort private constructor(
    clock: () -> Long,
) : BoardPort {
    private val events = GattEvents(clock)
    private var gatt: BluetoothGatt? = null
    private var serial: BluetoothGattCharacteristic? = null
    private var arrived: Long? = null

    override fun read(timeoutMs: Int): ByteArray {
        val got = events.pieces.poll(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
        if (got == null && !events.gone) return ByteArray(0)
        val (bytes, at) = got ?: (ByteArray(0) to 0L)
        if (bytes.isEmpty()) throw IOException("the board's Bluetooth link was lost")
        arrived = at
        return bytes
    }

    override fun arrivedAt(): Long? = arrived

    override fun write(
        bytes: ByteArray,
        timeoutMs: Int,
    ) {
        val gatt = gatt ?: throw IOException("not connected")
        val serial = serial ?: throw IOException("not connected")
        for (at in bytes.indices step CHUNK) {
            val chunk = bytes.copyOfRange(at, minOf(at + CHUNK, bytes.size))
            if (!send(gatt, serial, chunk) || !events.written.tryAcquire(timeoutMs.toLong(), TimeUnit.MILLISECONDS)) {
                throw IOException("the board took no write")
            }
        }
    }

    override fun close() {
        events.gone = true
        gatt?.disconnect()
        gatt?.close()
    }

    private fun connect(
        context: Context,
        device: BluetoothDevice,
    ) {
        val gatt = device.connectGatt(context, false, events, BluetoothDevice.TRANSPORT_LE)
        this.gatt = gatt
        if (gatt == null || !events.awaitStep()) notOpened()
        // Short connection events: a radio trip waits less, and the clock is read closer.
        gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
        if (!gatt.discoverServices() || !events.awaitStep()) notOpened()
        val serial = gatt.getService(SERIAL_SERVICE)?.getCharacteristic(SERIAL_CHARACTERISTIC)
        this.serial = serial
        if (!subscribe(gatt, serial ?: notOpened(OpenFailure.NO_PORT)) || !events.awaitStep()) notOpened()
    }

    private fun notOpened(why: OpenFailure = OpenFailure.REFUSED): Nothing {
        close()
        throw BoardNotOpened(why)
    }

    companion object {
        /** [device] connected and its serial characteristic's notifications on. */
        fun open(
            context: Context,
            device: BluetoothDevice,
            clock: () -> Long,
        ): BoardPort = BlePort(clock).apply { connect(context, device) }
    }
}

/** One write of [chunk], without response, as Android of each version takes it. */
@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
private fun send(
    gatt: BluetoothGatt,
    serial: BluetoothGattCharacteristic,
    chunk: ByteArray,
): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        gatt.writeCharacteristic(serial, chunk, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) ==
            BluetoothStatusCodes.SUCCESS
    } else {
        serial.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        serial.value = chunk
        gatt.writeCharacteristic(serial)
    }

/** The serial characteristic's notifications asked for; its answer comes as a step. */
@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
private fun subscribe(
    gatt: BluetoothGatt,
    serial: BluetoothGattCharacteristic,
): Boolean {
    val descriptor = serial.getDescriptor(NOTIFICATIONS) ?: return false
    if (!gatt.setCharacteristicNotification(serial, true)) return false
    val on = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        gatt.writeDescriptor(descriptor, on) == BluetoothStatusCodes.SUCCESS
    } else {
        descriptor.value = on
        gatt.writeDescriptor(descriptor)
    }
}
