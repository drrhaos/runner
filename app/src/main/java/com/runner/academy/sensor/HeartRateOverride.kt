package com.runner.academy.sensor

import kotlinx.coroutines.flow.StateFlow

/**
 * A heart-rate sensor that replaces the Bluetooth one for the whole process, with its own
 * pairing: the debug build's simulated sensor (`DebugSensors`). The real [PairedSensorStore] is
 * then neither read nor written, so the real device stays paired after the switch goes off.
 */
class HeartRateOverride(
    val sensor: HeartRateSensor,
    val paired: StateFlow<Map<SensorType, PairedSensor>>
)
