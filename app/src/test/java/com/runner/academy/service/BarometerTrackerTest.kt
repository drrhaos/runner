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

    /** A phone that never slept: uptime = elapsed realtime. */
    private fun tracker() = BarometerTracker(context, clockNanos = { now }, uptimeNanos = { now })

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
    fun `an event timestamp on elapsed realtime is used over the delivery time`() {
        val barometer = addBarometer()
        val tracker = tracker()
        tracker.start()
        now = 48 * sec
        // The first event, fresh, shows the clock
        send(barometer, 1000f, timestamp = 48 * sec)
        now = 100 * sec
        // Delivered late in a batch: 50 s and 51 s readings at 100 s
        send(barometer, 1000f, timestamp = 50 * sec)
        send(barometer, 1000f, timestamp = 51 * sec)

        assertTrue(tracker.altitudeAt(50 * sec) != null)
        assertNull(tracker.altitudeAt(99 * sec))
    }

    @Test
    fun `an event timestamp on uptime is moved by the sleep`() {
        val barometer = addBarometer()
        // The phone slept 30 s since boot: uptime is 30 s behind
        val tracker = BarometerTracker(context, clockNanos = { now }, uptimeNanos = { now - 30 * sec })
        tracker.start()
        now = 48 * sec
        send(barometer, 1000f, timestamp = 18 * sec)
        now = 100 * sec
        // Uptime 20 s = elapsed 50 s
        send(barometer, 1000f, timestamp = 20 * sec)

        assertTrue(tracker.altitudeAt(50 * sec) != null)
        assertNull(tracker.altitudeAt(20 * sec))
    }

    @Test
    fun `stamps on neither clock fall back to the delivery time until the stop`() {
        val barometer = addBarometer()
        val tracker = tracker()
        tracker.start()
        now = 100 * sec
        // 30 s off: plausible, but not this clock
        send(barometer, 1000f, timestamp = 70 * sec)
        now = 101 * sec
        // Even a stamp that looks right later is not trusted: the clock was decided
        send(barometer, 1000f, timestamp = 60 * sec)

        assertNull(tracker.altitudeAt(65 * sec))
        assertTrue(tracker.altitudeAt(100 * sec) != null)

        // A new start decides afresh
        tracker.stop()
        tracker.start()
        now = 200 * sec
        send(barometer, 1000f, timestamp = 200 * sec)
        now = 210 * sec
        send(barometer, 1000f, timestamp = 205 * sec)
        assertTrue(tracker.altitudeAt(205 * sec) != null)
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
