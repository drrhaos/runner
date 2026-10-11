package com.runner.academy.sensor

/**
 * The check before pairing, on the BLE thread: a [ConnectionMachine] of its own drives [link]
 * (so a 133 is closed and retried the same way) until the battery read after notifications
 * turned on, a device without the Heart Rate Service, Bluetooth off, or [timeoutMs]. Then it
 * disconnects and reports once to [onResult].
 *
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
    private var notifying = false
    private var done = false
    private val deadline = Any()

    fun start(address: String) {
        val m = ConnectionMachine(address, bluetoothOn = true)
        machine = m
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
        feed(event)
        when {
            machine?.failure == LinkFailure.NO_HEART_RATE_SERVICE ->
                finish(ProbeResult.Failed(ProbeFailure.NOT_HEART_RATE_SENSOR))
            event == LinkEvent.BtOff -> finish(ProbeResult.Failed(ProbeFailure.BLUETOOTH_OFF))
            event == LinkEvent.NotifyEnabled -> notifying = true
        }
    }

    /**
     * Comes after [LinkCommand.ReadBattery], which the machine sends once notifications are on.
     * The name is read while the connection is still open.
     */
    override fun onBattery(percent: Int?) {
        if (notifying) finish(ProbeResult.Ok(link.deviceName, percent))
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
    }
}
