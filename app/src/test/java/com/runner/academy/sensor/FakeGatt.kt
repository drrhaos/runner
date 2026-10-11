package com.runner.academy.sensor

import com.runner.academy.sensor.GattCharacteristic.Companion.BATTERY_LEVEL
import com.runner.academy.sensor.GattCharacteristic.Companion.HEART_RATE_MEASUREMENT

/** A [GattConnector] that records every connection; each one is a [FakeGattHandle]. */
class FakeGattConnector : GattConnector {

    class Attempt(val address: String, val autoConnect: Boolean, val callback: GattCallback, val handle: FakeGattHandle?)

    val attempts = mutableListOf<Attempt>()

    /** False: the next connects fail to start (null handle). */
    var accepts = true

    /** Characteristics with their properties that new connections will expose. */
    var characteristics: Map<GattCharacteristic, Int> = HRS_AND_BATTERY

    val last: Attempt get() = attempts.last()

    override fun connect(address: String, autoConnect: Boolean, callback: GattCallback): GattHandle? {
        val handle = if (accepts) FakeGattHandle(characteristics) else null
        attempts += Attempt(address, autoConnect, callback, handle)
        return handle
    }

    companion object {
        const val PROPERTY_READ = 0x02
        const val PROPERTY_NOTIFY = 0x10

        val HRS_ONLY = mapOf(HEART_RATE_MEASUREMENT to PROPERTY_NOTIFY)
        val HRS_AND_BATTERY = HRS_ONLY + mapOf(BATTERY_LEVEL to PROPERTY_READ)
        val HRS_AND_NOTIFYING_BATTERY = HRS_ONLY + mapOf(BATTERY_LEVEL to (PROPERTY_READ or PROPERTY_NOTIFY))
    }
}

/** One fake `BluetoothGatt`: records the operations started on it. */
class FakeGattHandle(private val characteristics: Map<GattCharacteristic, Int>) : GattHandle {

    /** Started operations: `discover`, `notify:<char>`, `read:<char>`, `close`. */
    val calls = mutableListOf<String>()

    /** False: operations refuse to start. */
    var accepts = true

    override var deviceName: String? = "HR Strap 10 1234"

    val closed: Boolean get() = "close" in calls

    override fun discoverServices(): Boolean = record("discover")

    override fun properties(characteristic: GattCharacteristic): Int? = characteristics[characteristic]

    override fun enableNotifications(characteristic: GattCharacteristic): Boolean =
        characteristic in characteristics && record("notify:${short(characteristic)}")

    override fun read(characteristic: GattCharacteristic): Boolean =
        characteristic in characteristics && record("read:${short(characteristic)}")

    override fun close() {
        calls += "close"
    }

    private fun record(call: String): Boolean {
        calls += call
        return accepts
    }

    companion object {
        /** `2a37` for Heart Rate Measurement. */
        fun short(characteristic: GattCharacteristic): String = characteristic.uuid.toString().substring(4, 8)
    }
}
