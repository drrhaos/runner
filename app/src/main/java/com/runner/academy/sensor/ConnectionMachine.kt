package com.runner.academy.sensor

/** What happened to the link: a lease, the adapter, a GATT callback or a timer firing. */
sealed interface LinkEvent {
    /** The first lease was taken. */
    data object Acquired : LinkEvent

    /** The last lease was released. */
    data object Released : LinkEvent
    data object BtOn : LinkEvent
    data object BtOff : LinkEvent
    data object Connected : LinkEvent

    /** The connection dropped or the attempt failed; [status] is the GATT status (133 and co). */
    data class Disconnected(val status: Int) : LinkEvent

    /** Service discovery finished; [hasHrs] is whether Heart Rate Service `0x180D` is there. */
    data class ServicesOk(val hasHrs: Boolean) : LinkEvent

    /** Notifications of Heart Rate Measurement `0x2A37` are on. */
    data object NotifyEnabled : LinkEvent

    /** A queued GATT operation failed or timed out. */
    data object OpFailed : LinkEvent
    data class Timeout(val id: LinkTimer) : LinkEvent

    /** A heart-rate notification arrived. */
    data object Sample : LinkEvent

    /** The paired device changed; null: none paired any more. */
    data class AddressChanged(val address: String?) : LinkEvent
}

/** What the GATT layer must do, in order. */
sealed interface LinkCommand {
    /** `close()` the current GATT object, if any. Always sent before a connect. */
    data object Close : LinkCommand

    /** `connectGatt(autoConnect = false)`: fast, gives up by itself. */
    data class ConnectDirect(val address: String) : LinkCommand

    /** `connectGatt(autoConnect = true)`: slow, waits for the device as long as it takes. */
    data class ConnectAuto(val address: String) : LinkCommand
    data object Discover : LinkCommand

    /** Write the CCCD of `0x2A37` to turn notifications on. */
    data object EnableHrNotify : LinkCommand

    /** Read Battery Level `0x2A19` (its failure is harmless). */
    data object ReadBattery : LinkCommand

    /** Fire [LinkEvent.Timeout] with [id] after [ms]; replaces a pending timer with the same id. */
    data class Schedule(val id: LinkTimer, val ms: Long) : LinkCommand
    data class Cancel(val id: LinkTimer) : LinkCommand
}

enum class LinkTimer {
    /** A direct attempt gives up. */
    CONNECT,

    /** The pause before the next attempt ends. */
    RETRY,

    /** No notification for too long while connected. */
    SILENCE
}

/** Why the machine gave up on a device; it stays given up until the address changes. */
enum class LinkFailure { NO_HEART_RATE_SERVICE }

/**
 * Connects to the paired sensor and keeps it connected while it is wanted (leased, paired,
 * Bluetooth on), with no clock or Bluetooth of its own: events in, GATT commands and timers out.
 *
 * - One GATT object at a time: every connect is directly preceded by [LinkCommand.Close].
 * - A direct connect with a [CONNECT_TIMEOUT_MS] timeout first. A drop, a failed or timed-out
 *   attempt, a failed setup step or [SILENCE_MS] without notifications closes and pauses
 *   [RETRY_PAUSES_MS] before each of up to three direct attempts; after that one pending
 *   auto connect with no timeout ([SensorLink.SEARCHING]). At worst, every direct attempt
 *   timing out, that is 4 × 30 s + 1 + 3 + 7 s = 131 s from the first connect.
 * - After every connection: discover, notifications on, battery read.
 * - A device without the Heart Rate Service is given up ([failure]) until the address changes.
 *
 * Not thread-safe: the caller feeds events from one thread.
 */
class ConnectionMachine(address: String? = null, bluetoothOn: Boolean = true) {

    /** [SEARCH_BACKOFF]: the pause after a failed auto connect, before the next one. */
    private enum class Phase { IDLE, CONNECTING, BACKOFF, SEARCHING, SEARCH_BACKOFF, SETUP, CONNECTED, FAILED }

    var address: String? = address
        private set

    /**
     * Set when the device turned out not to be a heart-rate sensor. Until the address changes
     * nothing reconnects to it: leases and Bluetooth coming back leave the link [SensorLink.IDLE].
     */
    var failure: LinkFailure? = null
        private set

    private var btOn = bluetoothOn
    private var acquired = false
    private var phase = Phase.IDLE

    /** Failed attempts (and drops) in a row since the last full setup or a fresh start. */
    private var failures = 0

    /** When the connection was made or the last notification came. */
    private var lastActivityMs = 0L
    private val pending = mutableSetOf<LinkTimer>()

    val link: SensorLink
        get() = when {
            !btOn -> SensorLink.BLUETOOTH_OFF
            else -> when (phase) {
                Phase.IDLE, Phase.FAILED -> SensorLink.IDLE
                Phase.CONNECTING, Phase.BACKOFF -> SensorLink.CONNECTING
                Phase.SEARCHING, Phase.SEARCH_BACKOFF -> SensorLink.SEARCHING
                Phase.SETUP, Phase.CONNECTED -> SensorLink.CONNECTED
            }
        }

    private val wanted: Boolean get() = acquired && btOn && address != null && failure == null

    fun on(event: LinkEvent, nowMs: Long): List<LinkCommand> {
        val out = mutableListOf<LinkCommand>()
        when (event) {
            LinkEvent.Acquired -> if (!acquired) {
                acquired = true
                if (wanted) startFresh(out)
            }
            LinkEvent.Released -> {
                acquired = false
                stop(out)
            }
            LinkEvent.BtOff -> {
                btOn = false
                stop(out)
            }
            LinkEvent.BtOn -> if (!btOn) {
                btOn = true
                if (wanted) startFresh(out)
            }
            is LinkEvent.AddressChanged -> if (event.address != address) {
                address = event.address
                failure = null
                when {
                    wanted -> startFresh(out)
                    phase != Phase.IDLE && phase != Phase.FAILED -> stop(out)
                    else -> phase = Phase.IDLE
                }
            }
            LinkEvent.Connected -> if (phase == Phase.CONNECTING || phase == Phase.SEARCHING) {
                cancelAll(out)
                phase = Phase.SETUP
                lastActivityMs = nowMs
                out += LinkCommand.Discover
                schedule(out, LinkTimer.SILENCE, SILENCE_MS)
            }
            is LinkEvent.ServicesOk -> if (phase == Phase.SETUP) {
                if (event.hasHrs) {
                    out += LinkCommand.EnableHrNotify
                } else {
                    failure = LinkFailure.NO_HEART_RATE_SERVICE
                    stop(out)
                }
            }
            LinkEvent.NotifyEnabled -> if (phase == Phase.SETUP) {
                phase = Phase.CONNECTED
                failures = 0
                out += LinkCommand.ReadBattery
            }
            LinkEvent.OpFailed -> if (phase == Phase.SETUP) fail(out)
            LinkEvent.Sample -> if (phase == Phase.SETUP || phase == Phase.CONNECTED) lastActivityMs = nowMs
            is LinkEvent.Disconnected -> when (phase) {
                Phase.CONNECTING, Phase.SEARCHING, Phase.SETUP, Phase.CONNECTED -> fail(out)
                else -> Unit
            }
            is LinkEvent.Timeout -> if (pending.remove(event.id)) onTimeout(event.id, nowMs, out)
        }
        return out
    }

    private fun onTimeout(id: LinkTimer, nowMs: Long, out: MutableList<LinkCommand>) {
        when (id) {
            LinkTimer.CONNECT -> fail(out)
            LinkTimer.RETRY -> if (failures > RETRY_PAUSES_MS.size) connectAuto(out) else connectDirect(out)
            LinkTimer.SILENCE -> {
                val quietMs = nowMs - lastActivityMs
                if (quietMs >= SILENCE_MS) fail(out) else schedule(out, LinkTimer.SILENCE, SILENCE_MS - quietMs)
            }
        }
    }

    /** A connection or an attempt is lost: close, then pause or fall back to auto connect. */
    private fun fail(out: MutableList<LinkCommand>) {
        val wasSearching = phase == Phase.SEARCHING
        cancelAll(out)
        failures++
        when {
            failures <= RETRY_PAUSES_MS.size -> {
                out += LinkCommand.Close
                phase = Phase.BACKOFF
                schedule(out, LinkTimer.RETRY, RETRY_PAUSES_MS[failures - 1])
            }
            // The auto connect itself failed: no tight loop, wait the longest pause
            wasSearching -> {
                out += LinkCommand.Close
                phase = Phase.SEARCH_BACKOFF
                schedule(out, LinkTimer.RETRY, RETRY_PAUSES_MS.last())
            }
            else -> connectAuto(out)
        }
    }

    private fun startFresh(out: MutableList<LinkCommand>) {
        failures = 0
        cancelAll(out)
        connectDirect(out)
    }

    private fun connectDirect(out: MutableList<LinkCommand>) {
        val target = address
        if (target == null) {
            stop(out)
            return
        }
        out += LinkCommand.Close
        out += LinkCommand.ConnectDirect(target)
        phase = Phase.CONNECTING
        schedule(out, LinkTimer.CONNECT, CONNECT_TIMEOUT_MS)
    }

    private fun connectAuto(out: MutableList<LinkCommand>) {
        val target = address
        if (target == null) {
            stop(out)
            return
        }
        out += LinkCommand.Close
        out += LinkCommand.ConnectAuto(target)
        phase = Phase.SEARCHING
    }

    /** Close and rest: given up on the device when there is a [failure], else idle. */
    private fun stop(out: MutableList<LinkCommand>) {
        cancelAll(out)
        out += LinkCommand.Close
        phase = if (failure != null) Phase.FAILED else Phase.IDLE
    }

    private fun schedule(out: MutableList<LinkCommand>, id: LinkTimer, ms: Long) {
        pending += id
        out += LinkCommand.Schedule(id, ms)
    }

    private fun cancelAll(out: MutableList<LinkCommand>) {
        LinkTimer.entries.filter { it in pending }.forEach { out += LinkCommand.Cancel(it) }
        pending.clear()
    }

    companion object {
        const val CONNECT_TIMEOUT_MS = 30_000L
        const val SILENCE_MS = 15_000L

        /** Pauses before the 1st, 2nd and 3rd direct retry. */
        val RETRY_PAUSES_MS = listOf(1_000L, 3_000L, 7_000L)
    }
}
