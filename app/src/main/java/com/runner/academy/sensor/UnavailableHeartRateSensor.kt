package com.runner.academy.sensor

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** The heart-rate sensor on a device without Bluetooth LE: always idle, never a sample. */
object UnavailableHeartRateSensor : HeartRateSensor {
    override val status: StateFlow<SensorStatus> = MutableStateFlow(SensorStatus(SensorLink.IDLE)).asStateFlow()
    override val samples: SharedFlow<HeartRateSample> = MutableSharedFlow<HeartRateSample>().asSharedFlow()
    override val diagnostics: SharedFlow<SensorDiagEvent> = MutableSharedFlow<SensorDiagEvent>().asSharedFlow()

    override fun acquire(owner: String): SensorLease = SensorLease { }
}
