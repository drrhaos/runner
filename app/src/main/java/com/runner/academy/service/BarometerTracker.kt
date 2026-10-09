package com.runner.academy.service

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.util.Log

/**
 * Pressure altitude during a workout from the barometer (`TYPE_PRESSURE`, 1 Hz). The math
 * lives in [PressureAltitudeHistory]; each GPS fix takes [altitudeAt] its own time (the point's
 * `baro_m`).
 *
 * Lifecycle: [start] → [stop]. A pause keeps listening (a reading a second costs nothing) so
 * the first fix after the resume has readings around it; a restored workout starts afresh —
 * there is no baseline to carry, only differences of the altitude matter.
 *
 * It never throws for a missing sensor: [start] returns false and [altitudeAt] is null. No
 * permission is needed.
 *
 * Sensor events arrive on the main looper; accessors are synchronised so any thread may read.
 * The clock of the events' own timestamps is decided on the first event after [start] and kept
 * until [stop] ([SensorClock]): elapsed realtime ([clockNanos]) or uptime ([uptimeNanos]),
 * moved onto elapsed realtime; on neither, the time of delivery.
 */
class BarometerTracker(
    context: Context,
    private val clockNanos: () -> Long = SystemClock::elapsedRealtimeNanos,
    private val uptimeNanos: () -> Long = { SystemClock.uptimeMillis() * 1_000_000L }
) {

    private val sensorManager = context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val history = PressureAltitudeHistory(toMeters = { hPa ->
        SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, hPa)
    })
    private var registeredSensor: Sensor? = null

    /** The clock of the event stamps, decided on the first event after [start]. */
    private var eventClock: SensorClock? = null

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val now = clockNanos()
            val uptime = uptimeNanos()
            synchronized(this@BarometerTracker) {
                if (registeredSensor == null) return
                val clock = eventClock ?: SensorClock.detect(event.timestamp, now, uptime).also {
                    eventClock = it
                    Log.i(TAG, "Barometer event clock: $it")
                }
                val time = clock.toElapsed(event.timestamp, now, uptime)
                event.values.firstOrNull()?.let { history.add(time, it) }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    /** The phone has a barometer. */
    val isAvailable: Boolean
        get() = sensorManager?.getDefaultSensor(Sensor.TYPE_PRESSURE) != null

    @get:Synchronized
    val isRunning: Boolean
        get() = registeredSensor != null

    /**
     * Pressure altitude (standard atmosphere, metres) at [elapsedRealtimeNanos] — a GPS fix's
     * time; 0 or a time in the future means now. Null when not running or without readings
     * around that moment.
     */
    @Synchronized
    fun altitudeAt(elapsedRealtimeNanos: Long): Float? {
        if (registeredSensor == null) return null
        val now = clockNanos()
        val time = if (elapsedRealtimeNanos <= 0L || elapsedRealtimeNanos > now) now else elapsedRealtimeNanos
        return history.altitudeAt(time)
    }

    /** Starts listening afresh. Returns false without a barometer or when it is refused. */
    @Synchronized
    fun start(): Boolean {
        stopListening()
        val manager = sensorManager ?: return false
        val sensor = manager.getDefaultSensor(Sensor.TYPE_PRESSURE) ?: run {
            Log.i(TAG, "No barometer on this device")
            return false
        }
        val registered = try {
            manager.registerListener(listener, sensor, SAMPLING_PERIOD_US)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Barometer registration failed: ${e.message}")
            false
        }
        if (!registered) return false
        registeredSensor = sensor
        return true
    }

    /** Unregisters the sensor, forgets the readings and the event clock. */
    @Synchronized
    fun stop() {
        stopListening()
    }

    private fun stopListening() {
        if (registeredSensor != null) {
            try {
                sensorManager?.unregisterListener(listener)
            } catch (e: RuntimeException) {
                Log.w(TAG, "Barometer unregister failed: ${e.message}")
            }
        }
        registeredSensor = null
        eventClock = null
        history.clear()
    }

    private companion object {
        const val TAG = "BarometerTracker"
        const val SAMPLING_PERIOD_US = 1_000_000
    }
}
