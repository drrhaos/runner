package com.runner.academy.service

import com.runner.academy.sensor.HeartRateSensor
import com.runner.academy.sensor.PairedSensor
import com.runner.academy.sensor.SensorType
import kotlinx.coroutines.flow.StateFlow

/**
 * What the workout service needs of the heart-rate sensor: the shared sensor, the pairing (to
 * know whether there is one and to follow a new one mid-workout) and whether connecting is
 * allowed (`BLUETOOTH_CONNECT` on 31+), which the foreground service type depends on.
 */
class HeartRateSource(
    val sensor: HeartRateSensor,
    val paired: StateFlow<Map<SensorType, PairedSensor>>,
    val connectGranted: () -> Boolean
) {
    val isPaired: Boolean get() = paired.value[SensorType.HEART_RATE] != null
}
