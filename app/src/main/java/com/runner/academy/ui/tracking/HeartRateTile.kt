package com.runner.academy.ui.tracking

import com.runner.academy.sensor.PairedSensor
import com.runner.academy.sensor.SensorLink

/**
 * Whether a tap on the heart-rate tile opens Devices: without a connected sensor (also during a
 * workout: its state lives in the service). Connected with the strap off there is nothing to fix
 * in the settings, the tile only shows. What the tile says is [HeartRateTileLook].
 */
enum class HeartRateTile {
    NO_SENSOR,
    DISCONNECTED,
    CONNECTED;

    val opensDevices: Boolean get() = this != CONNECTED

    companion object {
        fun of(paired: PairedSensor?, link: SensorLink): HeartRateTile = when {
            paired == null -> NO_SENSOR
            link != SensorLink.CONNECTED -> DISCONNECTED
            else -> CONNECTED
        }
    }
}
