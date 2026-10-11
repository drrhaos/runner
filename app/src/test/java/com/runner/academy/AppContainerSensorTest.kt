package com.runner.academy

import android.content.pm.PackageManager
import com.runner.academy.sensor.BleHeartRateSensor
import com.runner.academy.sensor.BleScanner
import com.runner.academy.sensor.ScanException
import com.runner.academy.sensor.ScanFailure
import com.runner.academy.sensor.SensorLink
import com.runner.academy.sensor.UnavailableHeartRateSensor
import com.runner.academy.sensor.UnavailableSensorScanner
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AppContainerSensorTest {

    private val app = RuntimeEnvironment.getApplication()

    private fun container(ble: Boolean): AppContainer {
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE, ble)
        return AppContainer(app)
    }

    @Test
    fun `without Bluetooth LE the sensor is an idle stub`() {
        val container = container(ble = false)
        val sensor = container.heartRateSensor
        assertSame(UnavailableHeartRateSensor, sensor)
        assertEquals(SensorLink.IDLE, sensor.status.value.link)
        sensor.acquire("test").release()

        assertSame(UnavailableSensorScanner, container.sensorScanner)
        val failure = runCatching { runBlocking { container.sensorScanner.scan().first() } }.exceptionOrNull()
        assertEquals(ScanFailure.UNSUPPORTED, (failure as ScanException).reason)
    }

    @Test
    fun `with Bluetooth LE the sensor and the scanner are one per container`() {
        val container = container(ble = true)
        assertTrue(container.heartRateSensor is BleHeartRateSensor)
        assertSame(container.heartRateSensor, container.heartRateSensor)
        assertTrue(container.sensorScanner is BleScanner)
        assertSame(container.pairedSensorStore, container.pairedSensorStore)
    }
}
