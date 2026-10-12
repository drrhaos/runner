package com.runner.academy.ui.tracking

import androidx.annotation.StringRes
import com.runner.academy.R
import com.runner.academy.sensor.PairedSensor
import com.runner.academy.sensor.SensorLink

/**
 * The heart-rate tile on the workout screen. Without a connected sensor a tap opens Devices
 * (also during a workout: its state lives in the service).
 *
 * @param a11y what TalkBack says for the tile when it opens Devices; null: the value itself.
 */
enum class HeartRateTile(@StringRes val a11y: Int?) {
    NO_SENSOR(R.string.tracking_hr_tile_no_sensor_a11y),
    DISCONNECTED(R.string.tracking_hr_tile_disconnected_a11y),
    CONNECTED(null);

    val opensDevices: Boolean get() = this != CONNECTED

    companion object {
        fun of(paired: PairedSensor?, link: SensorLink): HeartRateTile = when {
            paired == null -> NO_SENSOR
            link != SensorLink.CONNECTED -> DISCONNECTED
            else -> CONNECTED
        }
    }
}
