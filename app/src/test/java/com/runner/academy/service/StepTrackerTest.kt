package com.runner.academy.service

import android.Manifest
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
class StepTrackerTest {

    private lateinit var context: Context
    private lateinit var shadowSensors: ShadowSensorManager
    private var now = 0L

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        shadowSensors = shadowOf(context.getSystemService(Context.SENSOR_SERVICE) as SensorManager)
    }

    private fun tracker() = StepTracker(context) { now }

    private fun addSensor(type: Int): Sensor = ShadowSensor.newInstance(type).also { shadowSensors.addSensor(it) }

    private fun send(sensor: Sensor, vararg values: Float) {
        val event = ShadowSensorManager.createSensorEvent(values.size, sensor.type)
        values.copyInto(event.values)
        event.sensor = sensor
        shadowSensors.sendSensorEventToListeners(event)
    }

    @Test
    @Config(sdk = [28])
    fun `no sensor means unavailable, never a crash`() {
        val tracker = tracker()
        assertFalse(tracker.isAvailable)
        assertFalse(tracker.start())
        tracker.pause()
        tracker.resume()
        assertEquals(0, tracker.steps)
        assertNull(tracker.cadence)
        tracker.stop()
    }

    @Test
    @Config(sdk = [34])
    fun `missing permission on API 29+ means unavailable`() {
        addSensor(Sensor.TYPE_STEP_COUNTER)
        val tracker = tracker()
        assertFalse(tracker.isAvailable)
        assertFalse(tracker.start())
        assertFalse(tracker.isRunning)
    }

    @Test
    @Config(sdk = [34])
    fun `granted permission on API 29+ starts the counter`() {
        addSensor(Sensor.TYPE_STEP_COUNTER)
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.ACTIVITY_RECOGNITION)
        val tracker = tracker()
        assertTrue(tracker.isAvailable)
        assertTrue(tracker.start())
        assertEquals(StepTracker.Source.STEP_COUNTER, tracker.source)
    }

    @Test
    @Config(sdk = [28])
    fun `step counter events become steps and cadence, pause drops steps`() {
        val counter = addSensor(Sensor.TYPE_STEP_COUNTER)
        val tracker = tracker()
        assertTrue(tracker.start())
        send(counter, 1000f)
        for (s in 1..15) {
            now = s * 1_000_000_000L
            send(counter, 1000f + 3 * s)
        }
        assertEquals(45, tracker.steps)
        assertEquals(180f, tracker.cadence!!, 1f)

        tracker.pause()
        send(counter, 1100f)
        tracker.resume()
        send(counter, 1105f)
        assertEquals(50, tracker.steps)

        tracker.stop()
        assertFalse(tracker.isRunning)
        assertEquals(50, tracker.steps)
        assertNull(tracker.cadence)
    }

    @Test
    @Config(sdk = [28])
    fun `falls back to the step detector`() {
        val detector = addSensor(Sensor.TYPE_STEP_DETECTOR)
        val tracker = tracker()
        assertTrue(tracker.start(initialSteps = 10))
        assertEquals(StepTracker.Source.STEP_DETECTOR, tracker.source)
        repeat(3) { send(detector, 1f) }
        assertEquals(13, tracker.steps)
    }
}
