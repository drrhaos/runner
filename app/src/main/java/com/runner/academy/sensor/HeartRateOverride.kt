package com.runner.academy.sensor

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * A heart-rate sensor that replaces the Bluetooth one for the whole process, with its own
 * pairing: the debug build's simulated sensor (`DebugSensors`). The real [PairedSensorStore] is
 * then neither read nor written, so the real device stays paired after the switch goes off.
 */
class HeartRateOverride(
    val sensor: HeartRateSensor,
    pairing: Map<SensorType, PairedSensor>
) {
    private val _paired = MutableStateFlow(pairing)
    val paired: StateFlow<Map<SensorType, PairedSensor>> = _paired.asStateFlow()

    /** "Forget device" forgets the simulated one, until the next process start. */
    fun forget(type: SensorType) {
        _paired.update { it - type }
    }

    /** Pairing a real device while simulating is ignored: the real store is not written. */
    fun pair(sensor: PairedSensor) {
        Log.i(TAG, "Simulating: pairing ${sensor.type} ignored, turn the simulation off first")
    }

    private companion object {
        const val TAG = "HeartRateOverride"
    }
}
