package com.runner.academy.ui.devices

import androidx.annotation.StringRes
import com.runner.academy.R
import com.runner.academy.sensor.PairedSensor
import com.runner.academy.sensor.SensorLink

/** A found device's signal in words (not bars: readable at any font size and by TalkBack). */
enum class SignalLevel(@StringRes val text: Int) {
    EXCELLENT(R.string.add_sensor_signal_excellent),
    GOOD(R.string.add_sensor_signal_good),
    WEAK(R.string.add_sensor_signal_weak),
    VERY_WEAK(R.string.add_sensor_signal_very_weak);

    companion object {
        fun of(rssi: Int): SignalLevel = when {
            rssi >= -60 -> EXCELLENT
            rssi >= -75 -> GOOD
            rssi >= -85 -> WEAK
            else -> VERY_WEAK
        }
    }
}

/**
 * The subtitle of the Devices row in Settings: "Heart rate monitor: HR Strap 10 · connected".
 *
 * @param text takes [sensorName] as its argument when [hasSensor].
 * @param sensorName null for a sensor without a name (shown as "Unnamed").
 */
data class DevicesSummary(@StringRes val text: Int, val hasSensor: Boolean, val sensorName: String?) {

    companion object {
        fun of(paired: PairedSensor?, link: SensorLink): DevicesSummary {
            if (paired == null) return DevicesSummary(R.string.settings_devices_none, false, null)
            val text = when (link) {
                SensorLink.CONNECTED -> R.string.settings_devices_summary_connected
                SensorLink.BLUETOOTH_OFF -> R.string.settings_devices_summary_bt_off
                SensorLink.NO_PERMISSION -> R.string.settings_devices_summary_no_access
                SensorLink.IDLE, SensorLink.CONNECTING, SensorLink.SEARCHING -> R.string.settings_devices_summary
            }
            return DevicesSummary(text, true, paired.name?.takeIf { it.isNotBlank() })
        }
    }
}
