package com.runner.academy.ui.devices

import com.runner.academy.R
import com.runner.academy.sensor.PairedSensor
import com.runner.academy.sensor.SensorLink
import com.runner.academy.sensor.SensorType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DevicesTextsTest {

    private val strap = PairedSensor(SensorType.HEART_RATE, "AA:BB", "HR Strap 10", 1L, null, null)

    @Test
    fun `signal levels by rssi`() {
        assertEquals(SignalLevel.EXCELLENT, SignalLevel.of(-40))
        assertEquals(SignalLevel.EXCELLENT, SignalLevel.of(-60))
        assertEquals(SignalLevel.GOOD, SignalLevel.of(-61))
        assertEquals(SignalLevel.GOOD, SignalLevel.of(-75))
        assertEquals(SignalLevel.WEAK, SignalLevel.of(-76))
        assertEquals(SignalLevel.WEAK, SignalLevel.of(-85))
        assertEquals(SignalLevel.VERY_WEAK, SignalLevel.of(-86))
        assertEquals(R.string.add_sensor_signal_very_weak, SignalLevel.VERY_WEAK.text)
    }

    @Test
    fun `settings summary without a sensor`() {
        val summary = DevicesSummary.of(null, SensorLink.IDLE)

        assertEquals(R.string.settings_devices_none, summary.text)
        assertNull(summary.sensorName)
        assertEquals(false, summary.hasSensor)
    }

    @Test
    fun `settings summary by link`() {
        assertEquals(R.string.settings_devices_summary_connected, DevicesSummary.of(strap, SensorLink.CONNECTED).text)
        assertEquals(R.string.settings_devices_summary_bt_off, DevicesSummary.of(strap, SensorLink.BLUETOOTH_OFF).text)
        assertEquals(R.string.settings_devices_summary_no_access, DevicesSummary.of(strap, SensorLink.NO_PERMISSION).text)
        for (link in listOf(SensorLink.IDLE, SensorLink.CONNECTING, SensorLink.SEARCHING)) {
            assertEquals(R.string.settings_devices_summary, DevicesSummary.of(strap, link).text)
        }
        assertEquals("HR Strap 10", DevicesSummary.of(strap, SensorLink.IDLE).sensorName)
        assertEquals(true, DevicesSummary.of(strap, SensorLink.IDLE).hasSensor)
    }

    @Test
    fun `settings summary of a sensor without a name has no name`() {
        val summary = DevicesSummary.of(strap.copy(name = null), SensorLink.CONNECTED)

        assertNull(summary.sensorName)
        assertEquals(true, summary.hasSensor)
    }
}
