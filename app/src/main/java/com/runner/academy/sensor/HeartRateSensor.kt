package com.runner.academy.sensor

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The paired heart-rate sensor, shared by every screen and the workout service. The radio is
 * used only while someone holds a lease: the connection lives from the first [acquire] to the
 * last [SensorLease.release]. The sensor follows the paired address itself.
 */
interface HeartRateSensor {
    val status: StateFlow<SensorStatus>
    val samples: SharedFlow<HeartRateSample>
    val diagnostics: SharedFlow<SensorDiagEvent>

    /** Keep the sensor connected for [owner] (a name for logs, e.g. `"workout"`). */
    fun acquire(owner: String): SensorLease

    /**
     * Reads the signal strength of the connection once; it comes as a [SensorDiagEvent.Kind.RSSI]
     * on [diagnostics]. Nothing happens while not connected. For the diagnostics log only.
     */
    fun readRssi() {}
}

/** A hold on the sensor connection. Releasing it again has no effect. */
fun interface SensorLease {
    fun release()
}
