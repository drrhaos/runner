package com.runner.academy.sensor

import androidx.annotation.VisibleForTesting
import com.runner.academy.sensor.SensorDiagEvent.Kind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The paired heart-rate sensor over Bluetooth LE: a [ConnectionMachine] decides, a [GattLink]
 * does. One instance per process ([com.runner.academy.AppContainer.heartRateSensor]).
 *
 * - Leases are counted: the first one starts the machine, the last release closes the GATT.
 * - The device is the paired one in [store]; a replaced or forgotten device is followed at once.
 * - [SensorLink.NO_PERMISSION] while `BLUETOOTH_CONNECT` (31+) is missing; it is checked again
 *   on every [acquire], so after the grant the screen takes a new lease to connect.
 * - Battery and the last-seen time go to the status and back into [store] (last seen at most
 *   once a minute, and when the connection ends).
 * - [holdOff] keeps it off the device while the pairing probe talks to it.
 *
 * The constructor only reads the store; the link, the permission and everything after them run
 * on the BLE thread ([scheduler]). [acquire], [holdOff] and the releases may be called anywhere.
 *
 * @param bluetoothOn reads the adapter switch; read again once the link listens for changes.
 * @param nowMs the machine's clock (`SystemClock.elapsedRealtime`).
 * @param wallMs wall-clock time for last seen.
 */
class BleHeartRateSensor(
    private val store: PairedSensorStore,
    private val link: GattLink,
    private val scheduler: LinkScheduler,
    private val canConnect: () -> Boolean,
    private val bluetoothOn: () -> Boolean,
    private val nowMs: () -> Long,
    private val wallMs: () -> Long,
    scope: CoroutineScope
) : HeartRateSensor, GattLink.Listener {

    private val paired: PairedSensor? = store.get(SensorType.HEART_RATE)
    private val machine = ConnectionMachine(paired?.address, bluetoothOn())

    /** Checked on the BLE thread when the sensor starts and on every [acquire]. */
    private var permitted = true
    private var leases = 0

    /** [holdOff]s of the current device. */
    private var holds = 0

    /** Whether the machine was told [LinkEvent.Acquired]: leased, allowed and not held off. */
    private var machineAcquired = false

    private var batteryPercent: Int? = paired?.batteryPercent
    private var lastSeenAt: Long? = paired?.lastSeenAt

    /** The last-seen time written to [store]. */
    private var savedSeenAt: Long? = lastSeenAt

    private val _status = MutableStateFlow(currentStatus())
    override val status: StateFlow<SensorStatus> = _status.asStateFlow()

    private val _samples = MutableSharedFlow<HeartRateSample>(extraBufferCapacity = BUFFER)
    override val samples: SharedFlow<HeartRateSample> = _samples.asSharedFlow()

    private val _diagnostics = MutableSharedFlow<SensorDiagEvent>(extraBufferCapacity = BUFFER)
    override val diagnostics: SharedFlow<SensorDiagEvent> = _diagnostics.asSharedFlow()

    /** Leases held now (read on the BLE thread or in tests). */
    @VisibleForTesting
    internal val leaseCount: Int get() = leases

    init {
        scheduler.post {
            link.start(this)
            // A switch between the snapshot above and the receiver registration is not broadcast
            val on = bluetoothOn()
            if (on != (machine.link != SensorLink.BLUETOOTH_OFF)) feed(if (on) LinkEvent.BtOn else LinkEvent.BtOff)
            permitted = canConnect()
            publish()
        }
        store.changes
            .onEach { sensors -> scheduler.post { onPaired(sensors[SensorType.HEART_RATE]) } }
            .launchIn(scope)
    }

    override fun acquire(owner: String): SensorLease {
        val released = AtomicBoolean(false)
        scheduler.post {
            leases++
            permitted = canConnect()
            syncLease()
        }
        return SensorLease {
            if (released.compareAndSet(false, true)) {
                scheduler.post {
                    leases--
                    syncLease()
                }
            }
        }
    }

    /**
     * Keeps the sensor disconnected from [address] until released, when it is the sensor's
     * device: a peripheral often takes one connection only, and two GATT clients to one device
     * from one phone trip the stack. Another address: nothing happens. Leases taken meanwhile
     * connect after the release.
     */
    fun holdOff(address: String): SensorLease {
        val released = AtomicBoolean(false)
        var held = false
        scheduler.post {
            if (address == machine.address && !released.get()) {
                held = true
                holds++
                syncLease()
            }
        }
        return SensorLease {
            if (released.compareAndSet(false, true)) {
                scheduler.post {
                    if (held) {
                        holds--
                        syncLease()
                    }
                }
            }
        }
    }

    private fun syncLease() {
        val wanted = leases > 0 && permitted && holds == 0
        if (wanted != machineAcquired) {
            machineAcquired = wanted
            feed(if (wanted) LinkEvent.Acquired else LinkEvent.Released)
        } else {
            publish()
        }
    }

    private fun onPaired(sensor: PairedSensor?) {
        // Our own battery and last-seen writes come back here too: same address, nothing to do
        if (sensor?.address == machine.address) return
        batteryPercent = sensor?.batteryPercent
        lastSeenAt = sensor?.lastSeenAt
        savedSeenAt = lastSeenAt
        feed(LinkEvent.AddressChanged(sensor?.address))
    }

    override fun onEvent(event: LinkEvent) {
        feed(event)
    }

    override fun onHeartRate(value: ByteArray, elapsedNanos: Long) {
        seen(save = false)
        HeartRateParser.parse(value)?.let { _samples.tryEmit(it.toSample(elapsedNanos)) }
        publish()
    }

    override fun onBattery(percent: Int?) {
        if (percent == null) return
        batteryPercent = percent
        diag(Kind.BATTERY, percent)
        seen(save = true)
        publish()
    }

    override fun onGattError(status: Int?) {
        diag(Kind.GATT_ERROR, status)
    }

    override fun readRssi() {
        scheduler.post {
            if (machine.link == SensorLink.CONNECTED) link.readRssi()
        }
    }

    override fun onRssi(dbm: Int) {
        diag(Kind.RSSI, dbm)
    }

    private fun feed(event: LinkEvent) {
        val before = machine.link
        val commands = machine.on(event, nowMs())
        logEvent(event, before, commands)
        if (event is LinkEvent.Disconnected || event == LinkEvent.Released) saveSeen()
        for (command in commands) {
            when (command) {
                is LinkCommand.ConnectDirect -> diag(Kind.CONNECTING)
                is LinkCommand.ConnectAuto -> diag(Kind.AUTO_CONNECT)
                else -> Unit
            }
            link.execute(command)
        }
        publish()
    }

    private fun logEvent(event: LinkEvent, before: SensorLink, commands: List<LinkCommand>) {
        when (event) {
            LinkEvent.Connected -> if (machine.link == SensorLink.CONNECTED) diag(Kind.CONNECTED)
            is LinkEvent.Disconnected -> if (LinkCommand.Close in commands) diag(Kind.DISCONNECTED, event.status)
            LinkEvent.BtOff -> if (before != SensorLink.BLUETOOTH_OFF) diag(Kind.BT_OFF)
            LinkEvent.BtOn -> if (before == SensorLink.BLUETOOTH_OFF) diag(Kind.BT_ON)
            is LinkEvent.Timeout ->
                if (event.id == LinkTimer.SILENCE && LinkCommand.Close in commands) diag(Kind.SILENCE)
            else -> Unit
        }
    }

    /** Data came from the device now; [save] writes it to the store at once. */
    private fun seen(save: Boolean) {
        val now = wallMs()
        lastSeenAt = now
        val saved = savedSeenAt
        if (save || saved == null || now - saved >= SAVE_SEEN_EVERY_MS) saveSeen(force = true)
    }

    private fun saveSeen(force: Boolean = false) {
        val address = machine.address ?: return
        if (!force && lastSeenAt == savedSeenAt) return
        val seenAt = lastSeenAt
        val battery = batteryPercent
        store.update(SensorType.HEART_RATE, address) {
            it.copy(lastSeenAt = seenAt ?: it.lastSeenAt, batteryPercent = battery ?: it.batteryPercent)
        }
        savedSeenAt = seenAt
    }

    private fun currentStatus(): SensorStatus = SensorStatus(
        link = if (permitted) machine.link else SensorLink.NO_PERMISSION,
        batteryPercent = batteryPercent,
        lastSeenAt = lastSeenAt
    )

    private fun publish() {
        _status.value = currentStatus()
    }

    private fun diag(kind: Kind, value: Int? = null) {
        _diagnostics.tryEmit(SensorDiagEvent(kind, value))
    }

    companion object {
        /** How often a steady stream of samples rewrites the last-seen time in the store. */
        const val SAVE_SEEN_EVERY_MS = 60_000L
        private const val BUFFER = 64
    }
}
