package com.runner.academy.debug

import android.content.Context
import android.view.View
import androidx.core.content.edit
import com.google.android.material.switchmaterial.SwitchMaterial
import com.runner.academy.R
import com.runner.academy.sensor.HeartRateOverride
import com.runner.academy.sensor.PairedSensor
import com.runner.academy.sensor.SensorType
import kotlinx.coroutines.CoroutineScope

/**
 * Debug-only sensors: the hidden "simulate heart rate monitor" switch in Settings and, while it
 * is on, the [SimulatedHeartRateSensor] with a made-up pairing for the whole process. The flag
 * lives in its own preferences (`debug`) and is read once per process: the sensor in the
 * container is lazy and swapping it under a running workout is not worth the risk.
 */
object DebugSensors {

    const val PREFS_NAME = "debug"
    private const val KEY_SIMULATED_HR = "simulated_hr"

    /** A made-up address: nothing ever connects to it. */
    const val SIMULATED_ADDRESS = "00:00:00:00:00:00"

    fun bind(row: View) {
        val switch = row.findViewById<SwitchMaterial>(R.id.switch_debug_simulated_hr) ?: return
        val prefs = row.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        switch.isChecked = prefs.getBoolean(KEY_SIMULATED_HR, false)
        (row.findViewById<View>(R.id.row_debug_simulated_hr) ?: row).setOnClickListener { switch.toggle() }
        switch.setOnCheckedChangeListener { _, checked -> prefs.edit { putBoolean(KEY_SIMULATED_HR, checked) } }
    }

    fun heartRateOverride(context: Context, scope: CoroutineScope): HeartRateOverride? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_SIMULATED_HR, false)) return null
        val paired = PairedSensor(
            type = SensorType.HEART_RATE,
            address = SIMULATED_ADDRESS,
            name = context.getString(R.string.debug_simulated_hr_name),
            pairedAt = 0L,
            lastSeenAt = null,
            batteryPercent = null
        )
        return HeartRateOverride(
            sensor = SimulatedHeartRateSensor(scope),
            pairing = mapOf(SensorType.HEART_RATE to paired)
        )
    }
}
