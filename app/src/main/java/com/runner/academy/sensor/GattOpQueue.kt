package com.runner.academy.sensor

import android.bluetooth.BluetoothGatt.GATT_SUCCESS

/** A GATT operation that ends with a callback. */
sealed interface GattOp {
    data object Discover : GattOp

    /** Notifications on: the CCCD write ends with `onDescriptorWrite`. */
    data class EnableNotify(val characteristic: GattCharacteristic) : GattOp
    data class Read(val characteristic: GattCharacteristic) : GattOp
}

/**
 * Android's GATT client runs one operation at a time and silently drops a second one started
 * before the first one's callback. This queue starts the next operation only when the running
 * one has finished, failed or timed out ([OP_TIMEOUT_MS]: a lost callback must not stall the
 * link forever). Runs on the BLE thread.
 *
 * @param start starts an operation; false when it did not start (counts as a failure).
 * @param onFailed an operation failed: the GATT status, or null when it did not start or timed out.
 */
class GattOpQueue(
    private val scheduler: LinkScheduler,
    private val start: (GattOp) -> Boolean,
    private val onFailed: (GattOp, status: Int?) -> Unit,
    private val timeoutMs: Long = OP_TIMEOUT_MS
) {

    private val waiting = ArrayDeque<GattOp>()

    /** The operation waiting for its callback. */
    var running: GattOp? = null
        private set

    private val timer = Any()

    fun add(op: GattOp) {
        waiting.addLast(op)
        if (running == null) startNext()
    }

    /**
     * The callback of [op] came with [status]. Ignored (false) unless [op] is the running
     * operation; a non-zero status fails it.
     *
     * @return true when [op] was running and succeeded.
     */
    fun finish(op: GattOp, status: Int): Boolean {
        if (running != op) return false
        scheduler.cancel(timer)
        running = null
        if (status != GATT_SUCCESS) onFailed(op, status)
        startNext()
        return status == GATT_SUCCESS
    }

    /** The connection is gone: drop all operations, no callbacks. */
    fun clear() {
        waiting.clear()
        running = null
        scheduler.cancel(timer)
    }

    private fun startNext() {
        while (running == null) {
            val op = waiting.removeFirstOrNull() ?: return
            if (start(op)) {
                running = op
                scheduler.postDelayed(timer, timeoutMs) { onTimeout(op) }
            } else {
                onFailed(op, null)
            }
        }
    }

    private fun onTimeout(op: GattOp) {
        if (running != op) return
        running = null
        onFailed(op, null)
        startNext()
    }

    companion object {
        const val OP_TIMEOUT_MS = 5_000L
    }
}
