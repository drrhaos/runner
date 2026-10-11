package com.runner.academy.sensor

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import androidx.core.content.ContextCompat
import androidx.core.os.HandlerCompat
import java.util.UUID

/** [LinkScheduler] on a `Handler` of the BLE thread. */
class HandlerScheduler(private val handler: Handler) : LinkScheduler {

    override fun post(task: () -> Unit) {
        handler.post(task)
    }

    override fun postDelayed(token: Any, delayMs: Long, task: () -> Unit) {
        HandlerCompat.postDelayed(handler, task, token, delayMs)
    }

    override fun cancel(token: Any) {
        handler.removeCallbacksAndMessages(token)
    }
}

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

    override fun properties(service: UUID, characteristic: UUID): Int? =
        characteristic(service, characteristic)?.properties

    override fun enableNotifications(service: UUID, characteristic: UUID): Boolean {
        val target = characteristic(service, characteristic) ?: return false
        val cccd = target.getDescriptor(GattUuids.CCCD) ?: return false
        return try {
            gatt.setCharacteristicNotification(target, true) &&
                writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        } catch (e: SecurityException) {
            false
        }
    }

    override fun read(service: UUID, characteristic: UUID): Boolean {
        val target = characteristic(service, characteristic) ?: return false
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

    private fun characteristic(service: UUID, characteristic: UUID): BluetoothGattCharacteristic? =
        gatt.getService(service)?.getCharacteristic(characteristic)

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
        target.onDescriptorWrite(descriptor.characteristic.uuid, status)
    }

    override fun onCharacteristicRead(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        status: Int
    ) {
        target.onCharacteristicRead(characteristic.uuid, value, status)
    }

    /** Below 33 only. */
    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
        target.onCharacteristicRead(characteristic.uuid, characteristic.value ?: ByteArray(0), status)
    }

    override fun onCharacteristicChanged(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray
    ) {
        target.onCharacteristicChanged(characteristic.uuid, value)
    }

    /** Below 33 only. */
    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        target.onCharacteristicChanged(characteristic.uuid, characteristic.value ?: ByteArray(0))
    }
}

/** `ACTION_STATE_CHANGED` of the adapter, delivered on [handler]. */
class AndroidAdapterWatch(private val context: Context, private val handler: Handler) : AdapterStateWatch {

    private var receiver: BroadcastReceiver? = null

    override fun start(onState: (Int) -> Unit) {
        stop()
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                onState(intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR))
            }
        }
        // Exported: below 33 ContextCompat makes a not-exported receiver require an app-private
        // permission, which the Bluetooth process sending this does not hold. Safe: it is a
        // protected broadcast, no other app can send it.
        ContextCompat.registerReceiver(
            context, r, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), null, handler,
            ContextCompat.RECEIVER_EXPORTED
        )
        receiver = r
    }

    override fun stop() {
        receiver?.let { context.unregisterReceiver(it) }
        receiver = null
    }
}
