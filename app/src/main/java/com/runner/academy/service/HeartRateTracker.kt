package com.runner.academy.service

import android.os.SystemClock
import com.runner.academy.sensor.HeartRateDisplay
import com.runner.academy.sensor.HeartRateSensor
import com.runner.academy.sensor.SensorLease
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The heart rate during a workout, like [BarometerTracker]: [start] takes the sensor lease
 * `"workout"` and keeps the samples in a [HeartRateHistory]; each GPS fix takes [bpmAt] its own
 * time (the point's `hr`), the tile shows [display] once a second.
 *
 * Lifecycle: [start] → [stop]. A pause keeps the connection (the history goes on, no points are
 * made). The service knows nothing about Bluetooth: the lease keeps the sensor connected, the
 * sensor follows the paired device itself.
 *
 * Samples are collected on [scope]; accessors are synchronised so any thread may read.
 */
class HeartRateTracker(
    private val sensor: HeartRateSensor,
    private val clockNanos: () -> Long = SystemClock::elapsedRealtimeNanos,
    private val scope: CoroutineScope
) {

    private val history = HeartRateHistory()
    private var lease: SensorLease? = null
    private var collecting: Job? = null

    @get:Synchronized
    val isRunning: Boolean
        get() = lease != null

    @Synchronized
    fun start() {
        if (lease != null) return
        lease = sensor.acquire(LEASE_OWNER)
        collecting = scope.launch {
            sensor.samples.collect { sample ->
                synchronized(this@HeartRateTracker) {
                    if (lease != null) {
                        history.add(sample)
                        if (sample.bpm != null) {
                            lastValidNanos = maxOf(lastValidNanos ?: sample.elapsedNanos, sample.elapsedNanos)
                        }
                    }
                }
            }
        }
    }

    /** Gives the lease back and forgets the samples. */
    @Synchronized
    fun stop() {
        collecting?.cancel()
        collecting = null
        lease?.release()
        lease = null
        history.clear()
        lastValidNanos = null
    }

    /**
     * The time of the newest valid sample since [start] (kept past the 2-minute history); null
     * before the first one. The voice counts its 30 s from here.
     */
    @get:Synchronized
    var lastValidNanos: Long? = null
        private set

    /** The heart rate at [elapsedNanos] (a fix's `elapsedRealtimeNanos`); null when not running. */
    @Synchronized
    fun bpmAt(elapsedNanos: Long): Int? = history.bpmAt(elapsedNanos)

    /** Under the lease: a link still IDLE is the sensor about to connect ([HeartRateDisplay.ofLeased]). */
    @Synchronized
    fun display(nowNanos: Long = clockNanos()): HeartRateDisplay =
        HeartRateDisplay.ofLeased(history.latest(), sensor.status.value.link, nowNanos)

    companion object {
        const val LEASE_OWNER = "workout"
    }
}
