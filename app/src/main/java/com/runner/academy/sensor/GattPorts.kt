package com.runner.academy.sensor

import java.util.Locale
import java.util.UUID

/** The standard Bluetooth SIG UUIDs the sensors use. */
object GattUuids {
    val HEART_RATE_SERVICE: UUID = uuid16(0x180D)
    val BATTERY_SERVICE: UUID = uuid16(0x180F)

    /** Client Characteristic Configuration: notifications on/off. */
    val CCCD: UUID = uuid16(0x2902)

    /** A 16-bit SIG UUID on the Bluetooth base UUID. */
    fun uuid16(short: Int): UUID =
        UUID.fromString(String.format(Locale.ROOT, "0000%04x-0000-1000-8000-00805f9b34fb", short))
}

/** A characteristic: its [uuid] in the service [service]. */
data class GattCharacteristic(val service: UUID, val uuid: UUID) {
    companion object {
        val HEART_RATE_MEASUREMENT = GattCharacteristic(GattUuids.HEART_RATE_SERVICE, GattUuids.uuid16(0x2A37))
        val BATTERY_LEVEL = GattCharacteristic(GattUuids.BATTERY_SERVICE, GattUuids.uuid16(0x2A19))
    }
}

/**
 * Opens GATT connections. The Android one ([AndroidGattConnector]) wraps
 * `BluetoothDevice.connectGatt(TRANSPORT_LE)`; tests pass a fake.
 */
fun interface GattConnector {
    /**
     * Starts connecting to [address]; every callback for this connection goes to [callback] on
     * the BLE thread. Null when the attempt could not even start (bad address, no permission,
     * the stack refused): no callback will come.
     */
    fun connect(address: String, autoConnect: Boolean, callback: GattCallback): GattHandle?
}

/** One `BluetoothGatt` object. Each call starts an operation and says whether it started. */
interface GattHandle {
    /** The device name as the stack knows it, null when unknown or not allowed. */
    val deviceName: String?

    fun discoverServices(): Boolean

    /** The properties (`PROPERTY_*`) of [characteristic], null when the device has none. */
    fun properties(characteristic: GattCharacteristic): Int?

    /** Turns notifications of [characteristic] on: local registration and a CCCD write. */
    fun enableNotifications(characteristic: GattCharacteristic): Boolean

    fun read(characteristic: GattCharacteristic): Boolean

    /** Disconnects and frees the GATT client (`close()`, not just `disconnect()`). */
    fun close()
}

/** `BluetoothGattCallback` reduced to what the link needs. */
interface GattCallback {
    fun onConnectionStateChange(status: Int, connected: Boolean)
    fun onServicesDiscovered(status: Int)

    /** A CCCD write of [characteristic] finished. */
    fun onDescriptorWrite(characteristic: GattCharacteristic, status: Int)
    fun onCharacteristicRead(characteristic: GattCharacteristic, value: ByteArray, status: Int)
    fun onCharacteristicChanged(characteristic: GattCharacteristic, value: ByteArray)
}

/**
 * The BLE thread: everything the link, the machine and the sensor do runs as tasks on it, in
 * order. The Android one is a `Handler` on `HandlerThread("ble")` ([HandlerScheduler]).
 */
interface LinkScheduler {
    fun post(task: () -> Unit)

    /** Runs [task] after [delayMs] unless [cancel] is called with an equal [token] first. */
    fun postDelayed(token: Any, delayMs: Long, task: () -> Unit)
    fun cancel(token: Any)
}

/** Watches the Bluetooth adapter switch (`BluetoothAdapter.ACTION_STATE_CHANGED`). */
interface AdapterStateWatch {
    /** Calls [onState] with `BluetoothAdapter.STATE_*` on the BLE thread until [stop]. */
    fun start(onState: (Int) -> Unit)
    fun stop()
}
