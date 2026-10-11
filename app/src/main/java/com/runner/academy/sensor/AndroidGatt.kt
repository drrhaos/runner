package com.runner.academy.sensor

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import android.os.Handler

/**
 * `connectGatt` over LE with the callbacks on [handler] (the BLE thread). Every Bluetooth call
 * here may throw [SecurityException] when `BLUETOOTH_CONNECT` (31+) is missing or revoked: it
 * is handled as "did not start", like any other refusal of the stack.
 */
class AndroidGattConnector(
    private val context: Context,
    private val adapter: BluetoothAdapter,
    private val handler: Handler
) : GattConnector {

    override fun connect(address: String, autoConnect: Boolean, callback: GattCallback): GattHandle? {
        if (!BluetoothAdapter.checkBluetoothAddress(address)) return null
        val device = adapter.getRemoteDevice(address)
        val gatt = try {
            device.connectGatt(
                context, autoConnect, AndroidGattCallback(callback),
                BluetoothDevice.TRANSPORT_LE, BluetoothDevice.PHY_LE_1M_MASK, handler
            )
        } catch (e: SecurityException) {
            null
        }
        return gatt?.let(::AndroidGattHandle)
    }
}

private class AndroidGattHandle(private val gatt: BluetoothGatt) : GattHandle {

    override val deviceName: String?
        get() = try {
            gatt.device.name
        } catch (e: SecurityException) {
            null
        }

    override fun discoverServices(): Boolean = try {
        gatt.discoverServices()
    } catch (e: SecurityException) {
        false
    }

    override fun properties(characteristic: GattCharacteristic): Int? = find(characteristic)?.properties

    override fun enableNotifications(characteristic: GattCharacteristic): Boolean {
        val target = find(characteristic) ?: return false
        val cccd = target.getDescriptor(GattUuids.CCCD) ?: return false
        return try {
            gatt.setCharacteristicNotification(target, true) &&
                writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        } catch (e: SecurityException) {
            false
        }
    }

    override fun read(characteristic: GattCharacteristic): Boolean {
        val target = find(characteristic) ?: return false
        return try {
            gatt.readCharacteristic(target)
        } catch (e: SecurityException) {
            false
        }
    }

    override fun close() {
        try {
            gatt.close()
        } catch (e: SecurityException) {
            // Revoked permission: the process is about to be killed anyway
        }
    }

    private fun find(characteristic: GattCharacteristic): BluetoothGattCharacteristic? =
        gatt.getService(characteristic.service)?.getCharacteristic(characteristic.uuid)

    /** 33+ passes the value; below it is set on the descriptor (deprecated, shared state). */
    @Throws(SecurityException::class)
    private fun writeDescriptor(descriptor: BluetoothGattDescriptor, value: ByteArray): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = value
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(descriptor)
        }
}

/**
 * Forwards to [target]. Reads and notifications come with the value on 33+ and on the
 * characteristic below; overriding the new methods without `super` keeps the old ones from
 * being called twice on 33+.
 */
private class AndroidGattCallback(private val target: GattCallback) : BluetoothGattCallback() {

    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
        target.onConnectionStateChange(status, newState == BluetoothProfile.STATE_CONNECTED)
    }

    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
        target.onServicesDiscovered(status)
    }

    override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
        descriptor.characteristic.id()?.let { target.onDescriptorWrite(it, status) }
    }

    override fun onCharacteristicRead(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        status: Int
    ) {
        characteristic.id()?.let { target.onCharacteristicRead(it, value, status) }
    }

    /** Below 33 only. */
    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
        characteristic.id()?.let { target.onCharacteristicRead(it, characteristic.value ?: ByteArray(0), status) }
    }

    override fun onCharacteristicChanged(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray
    ) {
        characteristic.id()?.let { target.onCharacteristicChanged(it, value) }
    }

    /** Below 33 only. */
    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        characteristic.id()?.let { target.onCharacteristicChanged(it, characteristic.value ?: ByteArray(0)) }
    }

    /** Null only for a characteristic detached from its service, which discovery never gives. */
    private fun BluetoothGattCharacteristic.id(): GattCharacteristic? =
        service?.let { GattCharacteristic(it.uuid, uuid) }
}
