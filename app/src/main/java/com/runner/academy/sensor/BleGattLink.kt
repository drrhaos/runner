package com.runner.academy.sensor

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import com.runner.academy.sensor.GattUuids.BATTERY_LEVEL
import com.runner.academy.sensor.GattUuids.BATTERY_SERVICE
import com.runner.academy.sensor.GattUuids.HEART_RATE_MEASUREMENT
import com.runner.academy.sensor.GattUuids.HEART_RATE_SERVICE
import java.util.UUID

/**
 * Carries out a [ConnectionMachine]'s commands for one device and reports back what the radio
 * did. Everything runs on the BLE thread; the listener is never called from inside [execute]
 * (results are posted), so the machine is never fed while it is still deciding.
 */
interface GattLink {

    /** Starts watching the Bluetooth switch; from now on [listener] gets events. */
    fun start(listener: Listener)

    fun execute(command: LinkCommand)

    /** Closes the connection, drops the timers and stops watching the switch. */
    fun stop()

    /** The name of the device of the open connection, null when unknown. */
    val deviceName: String?

    interface Listener {
        /** Feed it to the machine. */
        fun onEvent(event: LinkEvent)

        /** A Heart Rate Measurement packet, notified at [elapsedNanos]. Comes after [LinkEvent.Sample]. */
        fun onHeartRate(value: ByteArray, elapsedNanos: Long)

        /** A battery level read or notified; null: the device has none or the read failed. */
        fun onBattery(percent: Int?)

        /** A GATT operation failed with [status]; null: it did not start or timed out. */
        fun onGattError(status: Int?)
    }
}

/** `BluetoothAdapter.ACTION_STATE_CHANGED` states as machine events. */
object BluetoothStates {
    const val STATE_OFF = BluetoothAdapter.STATE_OFF
    const val STATE_TURNING_ON = BluetoothAdapter.STATE_TURNING_ON
    const val STATE_ON = BluetoothAdapter.STATE_ON
    const val STATE_TURNING_OFF = BluetoothAdapter.STATE_TURNING_OFF

    /** Turning off already counts as off: the connection is closed before the stack goes down. */
    fun event(state: Int): LinkEvent? = when (state) {
        STATE_ON -> LinkEvent.BtOn
        STATE_TURNING_OFF, STATE_OFF -> LinkEvent.BtOff
        else -> null
    }
}

/**
 * The [GattLink] over a [GattConnector]:
 * - `Close` is `close()` of the GATT object (a bare `disconnect()` leaks the client slot and
 *   is what makes the stack answer 133 later); a connect closes the previous object first.
 * - `Discover`, `EnableHrNotify` (the CCCD `0x2902` of `0x2A37`) and `ReadBattery` (`0x2A19`,
 *   then its notifications when it has them) go through a [GattOpQueue]: one at a time, 5 s each.
 * - `Schedule`/`Cancel` are delayed tasks on the BLE thread that come back as [LinkEvent.Timeout].
 * - Callbacks of a closed connection are dropped: each connect starts a new generation.
 */
class BleGattLink(
    private val connector: GattConnector,
    private val adapter: AdapterStateWatch,
    private val scheduler: LinkScheduler,
    private val elapsedNanos: () -> Long
) : GattLink {

    private var listener: GattLink.Listener? = null
    private var gatt: GattHandle? = null

    /** Bumped on every connect and close: results of older connections are stale. */
    private var generation = 0

    private val queue = GattOpQueue(scheduler, start = ::startOp, onFailed = ::onOpFailed)

    override val deviceName: String? get() = gatt?.deviceName

    override fun start(listener: GattLink.Listener) {
        this.listener = listener
        adapter.start { state -> BluetoothStates.event(state)?.let { event -> deliver { onEvent(event) } } }
    }

    override fun stop() {
        adapter.stop()
        close()
        LinkTimer.entries.forEach { scheduler.cancel(timerToken(it)) }
        listener = null
    }

    override fun execute(command: LinkCommand) {
        when (command) {
            LinkCommand.Close -> close()
            is LinkCommand.ConnectDirect -> connect(command.address, autoConnect = false)
            is LinkCommand.ConnectAuto -> connect(command.address, autoConnect = true)
            LinkCommand.Discover -> queue.add(GattOp.Discover)
            LinkCommand.EnableHrNotify -> queue.add(GattOp.EnableNotify(HEART_RATE_SERVICE, HEART_RATE_MEASUREMENT))
            LinkCommand.ReadBattery -> readBattery()
            is LinkCommand.Schedule -> {
                val token = timerToken(command.id)
                scheduler.cancel(token)
                scheduler.postDelayed(token, command.ms) { listener?.onEvent(LinkEvent.Timeout(command.id)) }
            }
            is LinkCommand.Cancel -> scheduler.cancel(timerToken(command.id))
        }
    }

    private fun connect(address: String, autoConnect: Boolean) {
        close()
        val gen = generation
        gatt = connector.connect(address, autoConnect, Callback(gen))
        if (gatt == null) deliver(gen) { onEvent(LinkEvent.Disconnected(GATT_FAILURE)) }
    }

    private fun close() {
        queue.clear()
        gatt?.close()
        gatt = null
        generation++
    }

    private fun readBattery() {
        val properties = gatt?.properties(BATTERY_SERVICE, BATTERY_LEVEL)
        if (properties != null && properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) {
            queue.add(GattOp.Read(BATTERY_SERVICE, BATTERY_LEVEL))
        } else {
            deliver { onBattery(null) }
        }
    }

    private fun startOp(op: GattOp): Boolean {
        val g = gatt ?: return false
        return when (op) {
            GattOp.Discover -> g.discoverServices()
            is GattOp.EnableNotify -> g.enableNotifications(op.service, op.characteristic)
            is GattOp.Read -> g.read(op.service, op.characteristic)
        }
    }

    private fun onOpFailed(op: GattOp, status: Int?) {
        val battery = when (op) {
            GattOp.Discover -> false
            is GattOp.EnableNotify -> op.characteristic == BATTERY_LEVEL
            is GattOp.Read -> true
        }
        deliver {
            onGattError(status)
            when {
                // A battery failure is harmless: the heart rate works without it
                !battery -> onEvent(LinkEvent.OpFailed)
                op is GattOp.Read -> onBattery(null)
            }
        }
    }

    /** Posts [call] to the listener; dropped if the connection of [gen] is gone by then. */
    private fun deliver(gen: Int = generation, call: GattLink.Listener.() -> Unit) {
        scheduler.post { if (gen == generation) listener?.call() }
    }

    /** One object per timer of this link: `Handler.removeCallbacksAndMessages` matches by identity. */
    private val timerTokens = LinkTimer.entries.associateWith { Any() }

    private fun timerToken(id: LinkTimer): Any = timerTokens.getValue(id)

    private inner class Callback(private val gen: Int) : GattCallback {

        private val current: Boolean get() = gen == generation

        override fun onConnectionStateChange(status: Int, connected: Boolean) {
            if (!current) return
            if (connected && status == BluetoothGatt.GATT_SUCCESS) {
                deliver(gen) { onEvent(LinkEvent.Connected) }
            } else {
                queue.clear()
                deliver(gen) { onEvent(LinkEvent.Disconnected(status)) }
            }
        }

        override fun onServicesDiscovered(status: Int) {
            if (!current || !queue.finish(GattOp.Discover, status)) return
            val hasHrs = gatt?.properties(HEART_RATE_SERVICE, HEART_RATE_MEASUREMENT) != null
            deliver(gen) { onEvent(LinkEvent.ServicesOk(hasHrs)) }
        }

        override fun onDescriptorWrite(characteristic: UUID, status: Int) {
            if (!current) return
            val service = serviceOf(characteristic) ?: return
            if (!queue.finish(GattOp.EnableNotify(service, characteristic), status)) return
            if (characteristic == HEART_RATE_MEASUREMENT) deliver(gen) { onEvent(LinkEvent.NotifyEnabled) }
        }

        override fun onCharacteristicRead(characteristic: UUID, value: ByteArray, status: Int) {
            if (!current || characteristic != BATTERY_LEVEL) return
            if (!queue.finish(GattOp.Read(BATTERY_SERVICE, BATTERY_LEVEL), status)) return
            val percent = batteryPercent(value)
            deliver(gen) { onBattery(percent) }
            val properties = gatt?.properties(BATTERY_SERVICE, BATTERY_LEVEL) ?: 0
            if (percent != null && properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
                queue.add(GattOp.EnableNotify(BATTERY_SERVICE, BATTERY_LEVEL))
            }
        }

        override fun onCharacteristicChanged(characteristic: UUID, value: ByteArray) {
            if (!current) return
            when (characteristic) {
                HEART_RATE_MEASUREMENT -> {
                    val at = elapsedNanos()
                    deliver(gen) {
                        onEvent(LinkEvent.Sample)
                        onHeartRate(value, at)
                    }
                }
                BATTERY_LEVEL -> batteryPercent(value)?.let { deliver(gen) { onBattery(it) } }
            }
        }
    }

    companion object {
        /** The status of a connect that did not even start (`BluetoothGatt.GATT_FAILURE`). */
        const val GATT_FAILURE = BluetoothGatt.GATT_FAILURE

        private fun serviceOf(characteristic: UUID): UUID? = when (characteristic) {
            HEART_RATE_MEASUREMENT -> HEART_RATE_SERVICE
            BATTERY_LEVEL -> BATTERY_SERVICE
            else -> null
        }

        /** Battery Level is one byte, 0–100 %. */
        private fun batteryPercent(value: ByteArray): Int? =
            value.firstOrNull()?.toInt()?.and(0xFF)?.takeIf { it <= 100 }
    }
}
