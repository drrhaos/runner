package com.runner.academy.sensor

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * The check before pairing, on the BLE thread: connect, discover, the Heart Rate Service
 * (`0x180D` with `0x2A37`) is there, read the battery; then disconnect and report once to
 * [onResult]. A [ConnectionMachine] of its own drives the connection, so a 133 or a drop is
 * closed and retried the same way, until [timeoutMs].
 *
 * Notifications are not turned on: the check does not depend on a CCCD write, and a device
 * that refuses one still passes (the sensor's own setup retries it on every connection).
 * Not bonded: the Heart Rate Service needs no encryption.
 */
class SensorProbe(
    private val link: GattLink,
    private val scheduler: LinkScheduler,
    private val nowMs: () -> Long,
    private val onResult: (ProbeResult) -> Unit,
    private val timeoutMs: Long = PROBE_TIMEOUT_MS
) : GattLink.Listener {

    private var machine: ConnectionMachine? = null

    /** The services of the current connection include the Heart Rate Service. */
    private var hasHrs = false
    private var done = false
    private val deadline = Any()

    fun start(address: String) {
        machine = ConnectionMachine(address, bluetoothOn = true)
        link.start(this)
        scheduler.postDelayed(deadline, timeoutMs) { finish(ProbeResult.Failed(ProbeFailure.UNREACHABLE)) }
        feed(LinkEvent.Acquired)
    }

    /** Disconnects; no result. */
    fun cancel() {
        finish(null)
    }

    override fun onEvent(event: LinkEvent) {
        if (done) return
        when (event) {
            // The machine would go on to notifications; the probe reads the battery instead
            is LinkEvent.ServicesOk ->
                if (event.hasHrs) {
                    hasHrs = true
                    link.execute(LinkCommand.ReadBattery)
                } else {
                    finish(ProbeResult.Failed(ProbeFailure.NOT_HEART_RATE_SENSOR))
                }
            LinkEvent.BtOff -> finish(ProbeResult.Failed(ProbeFailure.BLUETOOTH_OFF))
            else -> {
                if (event is LinkEvent.Disconnected || event == LinkEvent.Connected) hasHrs = false
                feed(event)
            }
        }
    }

    /** The answer to [LinkCommand.ReadBattery]; the name is read while still connected. */
    override fun onBattery(percent: Int?) {
        if (!done && hasHrs) finish(ProbeResult.Ok(link.deviceName, percent))
    }

    override fun onHeartRate(value: ByteArray, elapsedNanos: Long) = Unit

    override fun onGattError(status: Int?) = Unit

    private fun feed(event: LinkEvent) {
        machine?.on(event, nowMs())?.forEach(link::execute)
    }

    private fun finish(result: ProbeResult?) {
        if (done) return
        done = true
        scheduler.cancel(deadline)
        feed(LinkEvent.Released)
        link.stop()
        result?.let(onResult)
    }

    companion object {
        /** Long enough for a direct connect, a 133 and one retry, and the setup. */
        const val PROBE_TIMEOUT_MS = 30_000L

        /**
         * Probes [address] over [link], suspending until the result; cancelling disconnects.
         * [holdOff] keeps the heart-rate sensor off the device meanwhile (see
         * [BleHeartRateSensor.holdOff]): its release comes after the probe has closed.
         */
        suspend fun run(
            address: String,
            link: GattLink,
            scheduler: LinkScheduler,
            nowMs: () -> Long,
            holdOff: (String) -> SensorLease
        ): ProbeResult {
            // Posted before the probe starts: the sensor closes its GATT first
            val hold = holdOff(address)
            try {
                return suspendCancellableCoroutine { cont ->
                    val probe = SensorProbe(link, scheduler, nowMs, onResult = { cont.resume(it) })
                    scheduler.post { probe.start(address) }
                    cont.invokeOnCancellation { scheduler.post { probe.cancel() } }
                }
            } finally {
                hold.release()
            }
        }
    }
}
