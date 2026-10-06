package com.runner.academy.service

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.util.Log
import com.runner.academy.util.StepTrackingAccess

/**
 * Counts steps during a workout from the hardware step sensor: `TYPE_STEP_COUNTER`, falling
 * back to `TYPE_STEP_DETECTOR`. The math lives in [StepCountAccumulator].
 *
 * Lifecycle: [start] → ([pause] / [resume])* → [stop]. [steps] is the count since [start]
 * (plus `initialSteps` when continuing a restored workout); steps during a pause are not
 * counted. [cadence] is steps/min over the last ~12 s, `null` when unknown.
 *
 * It never throws for a missing sensor or permission: [start] returns false and the tracker
 * reports nothing ([isRunning] false, [steps] 0, [cadence] null). Checking the user's
 * setting is the caller's job (`StepTrackingAccess.isStepTrackingAllowed`).
 *
 * Sensor events arrive on the main looper; accessors are synchronised so any thread may read.
 * Event timestamps are not used (their clock base differs on some devices); the time of
 * delivery from [clockNanos] is used instead.
 */
class StepTracker(
    context: Context,
    private val clockNanos: () -> Long = SystemClock::elapsedRealtimeNanos
) {

    enum class Source { STEP_COUNTER, STEP_DETECTOR }

    private val appContext = context.applicationContext
    private val sensorManager = appContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val accumulator = StepCountAccumulator()
    private var registeredSensor: Sensor? = null
    private var hasStarted = false

    /** Which sensor is in use while running, `null` when not running. */
    @get:Synchronized
    var source: Source? = null
        private set

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val now = clockNanos()
            synchronized(this@StepTracker) {
                if (registeredSensor == null) return
                when (event.sensor.type) {
                    Sensor.TYPE_STEP_COUNTER ->
                        event.values.firstOrNull()?.let { accumulator.onCounterValue(it.toLong(), now) }
                    Sensor.TYPE_STEP_DETECTOR ->
                        accumulator.onDetectedSteps(event.values.size.coerceAtLeast(1), now)
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    /** A step sensor exists and the permission is granted (or not needed). */
    val isAvailable: Boolean
        get() = findSensor() != null && StepTrackingAccess.hasPermission(appContext)

    @get:Synchronized
    val isRunning: Boolean
        get() = registeredSensor != null

    /** Steps since [start], excluding pauses; keeps the final count after [stop], 0 if never started. */
    @get:Synchronized
    val steps: Int
        get() = if (hasStarted) accumulator.steps else 0

    /**
     * Steps at [elapsedRealtimeNanos] (e.g. a GPS fix's time; batched fixes arrive late);
     * a time of 0 or in the future means now. 0 if never started.
     */
    @Synchronized
    fun stepsAt(elapsedRealtimeNanos: Long): Int {
        if (!hasStarted) return 0
        val now = clockNanos()
        return if (elapsedRealtimeNanos <= 0L || elapsedRealtimeNanos >= now) {
            accumulator.steps
        } else {
            accumulator.stepsAt(elapsedRealtimeNanos)
        }
    }

    /** Steps per minute over the recent window, `null` when unknown, paused or not running. */
    @get:Synchronized
    val cadence: Float?
        get() = if (registeredSensor != null) accumulator.cadence(clockNanos()) else null

    /**
     * Starts counting; [initialSteps] continues a restored workout. Returns false (and stays
     * unavailable) without a sensor or permission. Calling it while running restarts the count.
     */
    @Synchronized
    fun start(initialSteps: Int = 0): Boolean {
        stopListening()
        hasStarted = false
        val manager = sensorManager ?: return false
        if (!StepTrackingAccess.hasPermission(appContext)) {
            Log.i(TAG, "Step permission not granted; steps unavailable")
            return false
        }
        val sensor = findSensor() ?: run {
            Log.i(TAG, "No step sensor on this device")
            return false
        }
        accumulator.start(clockNanos(), initialSteps)
        val registered = try {
            // No batching asked for: auto-pause resumes on the first steps. The 3-argument
            // overload already means a latency of 0; stated explicitly so it stays that way.
            manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL, MAX_REPORT_LATENCY_US)
        } catch (e: SecurityException) {
            Log.w(TAG, "Step sensor refused: ${e.message}")
            false
        } catch (e: RuntimeException) {
            Log.w(TAG, "Step sensor registration failed: ${e.message}")
            false
        }
        if (!registered) return false
        hasStarted = true
        registeredSensor = sensor
        source = if (sensor.type == Sensor.TYPE_STEP_COUNTER) Source.STEP_COUNTER else Source.STEP_DETECTOR
        return true
    }

    /**
     * Stops counting steps until [resume]. The listener stays registered so the step counter's
     * baseline keeps moving and paused steps are dropped.
     */
    @Synchronized
    fun pause() {
        if (registeredSensor != null) accumulator.pause()
    }

    @Synchronized
    fun resume() {
        if (registeredSensor != null) accumulator.resume(clockNanos())
    }

    /** Unregisters the sensor; [steps] keeps the final count. */
    @Synchronized
    fun stop() {
        stopListening()
    }

    private fun stopListening() {
        if (registeredSensor != null) {
            try {
                sensorManager?.unregisterListener(listener)
            } catch (e: RuntimeException) {
                Log.w(TAG, "Step sensor unregister failed: ${e.message}")
            }
        }
        registeredSensor = null
        source = null
    }

    private fun findSensor(): Sensor? {
        val manager = sensorManager ?: return null
        return manager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
            ?: manager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
    }

    private companion object {
        const val TAG = "StepTracker"
        const val MAX_REPORT_LATENCY_US = 0
    }
}
