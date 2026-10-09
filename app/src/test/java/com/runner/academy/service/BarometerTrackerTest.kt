package com.runner.academy.service

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSensor
import org.robolectric.shadows.ShadowSensorManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BarometerTrackerTest {

    private lateinit var context: Context
    private lateinit var shadowSensors: ShadowSensorManager
    private var now = 0L
    private val sec = 1_000_000_000L

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        shadowSensors = shadowOf(context.getSystemService(Context.SENSOR_SERVICE) as SensorManager)
    }

    private fun tracker() = BarometerTracker(context) { now }

    private fun addBarometer(): Sensor =
        ShadowSensor.newInstance(Sensor.TYPE_PRESSURE).also { shadowSensors.addSensor(it) }

    private fun send(sensor: Sensor, hPa: Float, timestamp: Long = 0L) {
        val event = ShadowSensorManager.createSensorEvent(1, sensor.type)
        event.values[0] = hPa
        event.sensor = sensor
        event.timestamp = timestamp
        shadowSensors.sendSensorEventToListeners(event)
    }

    @Test
    fun `no barometer means no altitude, never a crash`() {
        val tracker = tracker()
        assertFalse(tracker.isAvailable)
        assertFalse(tracker.start())
        assertFalse(tracker.isRunning)
        assertNull(tracker.altitudeAt(0L))
        tracker.stop()
    }

    @Test
    fun `readings become the standard atmosphere altitude of the fix's moment`() {
        val barometer = addBarometer()
        val tracker = tracker()
        assertTrue(tracker.isAvailable)
        assertTrue(tracker.start())
        for (s in 1..20) {
            now = s * sec
            // Climbing about 1 m a second: 0.12 hPa
            send(barometer, 1000f - 0.12f * s)
        }
        val expected = SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, 1000f - 0.12f * 10)

        // A fix of 10 s that arrives at 20 s gets the altitude of 10 s, not the latest
        assertEquals(expected, tracker.altitudeAt(10 * sec)!!, 0.01f)
        // 0 means now
        assertEquals(
            SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, 1000f - 0.12f * 19),
            tracker.altitudeAt(0L)!!,
            0.01f
        )
    }

    @Test
    fun `an event timestamp on the same clock is used over the delivery time`() {
        val barometer = addBarometer()
        val tracker = tracker()
        tracker.start()
        now = 100 * sec
        // Delivered late in a batch: 50 s and 51 s readings at 100 s
        send(barometer, 1000f, timestamp = 50 * sec)
        send(barometer, 1000f, timestamp = 51 * sec)

        assertTrue(tracker.altitudeAt(50 * sec) != null)
        assertNull(tracker.altitudeAt(99 * sec))
    }

    @Test
    fun `stop unregisters and forgets the readings, start begins afresh`() {
        val barometer = addBarometer()
        val tracker = tracker()
        tracker.start()
        now = 5 * sec
        send(barometer, 1000f)
        tracker.stop()
        assertFalse(tracker.isRunning)
        assertNull(tracker.altitudeAt(5 * sec))
        send(barometer, 1000f)
        assertTrue(tracker.start())
        assertNull(tracker.altitudeAt(5 * sec))
    }
}
