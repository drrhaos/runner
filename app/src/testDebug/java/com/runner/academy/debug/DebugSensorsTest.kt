package com.runner.academy.debug

import android.content.Context
import android.content.pm.PackageManager
import android.view.LayoutInflater
import androidx.core.content.edit
import com.google.android.material.switchmaterial.SwitchMaterial
import com.runner.academy.AppContainer
import com.runner.academy.R
import com.runner.academy.sensor.BleHeartRateSensor
import com.runner.academy.sensor.PairedSensor
import com.runner.academy.sensor.PairedSensorStore
import com.runner.academy.sensor.SensorType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DebugSensorsTest {

    private val app = RuntimeEnvironment.getApplication()
    private val debugPrefs get() = app.getSharedPreferences(DebugSensors.PREFS_NAME, Context.MODE_PRIVATE)
    private val real = PairedSensor(SensorType.HEART_RATE, "AA:BB:CC:DD:EE:FF", "HR Strap 10", 1L, null, null)

    @Before
    fun setUp() {
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE, true)
        debugPrefs.edit { clear() }
        app.getSharedPreferences(PairedSensorStore.PREFS_NAME, Context.MODE_PRIVATE).edit { clear() }
    }

    @Test
    fun `switch off - the real sensor and the real pairing`() {
        val container = AppContainer(app)
        container.pairedSensorStore.put(real)

        assertTrue(container.heartRateSensor is BleHeartRateSensor)
        assertSame(container.pairedSensorStore.changes, container.pairedSensors)
    }

    @Test
    fun `switch on - the simulated sensor and its own pairing, the store untouched`() {
        AppContainer(app).pairedSensorStore.put(real)
        debugPrefs.edit { putBoolean("simulated_hr", true) }

        val container = AppContainer(app)

        assertTrue(container.heartRateSensor is SimulatedHeartRateSensor)
        val paired = container.pairedSensors.value.getValue(SensorType.HEART_RATE)
        assertEquals(DebugSensors.SIMULATED_ADDRESS, paired.address)
        assertEquals(app.getString(R.string.debug_simulated_hr_name), paired.name)
        assertEquals(real, container.pairedSensorStore.get(SensorType.HEART_RATE))
    }

    @Test
    fun `the settings row writes the flag`() {
        val themed = android.view.ContextThemeWrapper(app, R.style.Theme_Runner)
        val row = LayoutInflater.from(themed).inflate(R.layout.debug_settings_row, null)
        DebugSensors.bind(row)
        val switch = row.findViewById<SwitchMaterial>(R.id.switch_debug_simulated_hr)

        row.performClick()

        assertTrue(switch.isChecked)
        assertTrue(debugPrefs.getBoolean("simulated_hr", false))
    }

    @Test
    fun `switch off gives no override`() {
        assertNull(DebugSensors.heartRateOverride(app, kotlinx.coroutines.MainScope()))
    }
}
