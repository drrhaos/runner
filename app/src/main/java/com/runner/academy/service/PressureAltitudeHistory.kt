package com.runner.academy.service

import com.runner.academy.util.median
import kotlin.math.abs
import kotlin.math.pow

/**
 * Pure bookkeeping behind [BarometerTracker]: pressure readings turned into standard-atmosphere
 * altitudes and kept for [historyNanos], so a GPS fix that arrives late (batched with the
 * screen off) still gets the altitude of its own moment, like `StepCountAccumulator.stepsAt`.
 *
 * [altitudeAt] is the median of the readings within ±[halfWindowNanos] of the moment: a single
 * spike (a gust on the vent, a hand over the phone) is outvoted. There is no calibration — the
 * absolute value is off by the weather, only differences matter for the gain.
 *
 * Timestamps are one monotonic clock in nanoseconds (`elapsedRealtimeNanos`). Not thread-safe;
 * [BarometerTracker] synchronises access. No Android types, so it is unit-tested on the JVM.
 *
 * @param toMeters pressure (hPa) to altitude (m); the tracker passes `SensorManager.getAltitude`
 */
class PressureAltitudeHistory(
    private val historyNanos: Long = DEFAULT_HISTORY_NANOS,
    private val halfWindowNanos: Long = DEFAULT_HALF_WINDOW_NANOS,
    private val toMeters: (Float) -> Float = ::standardAltitudeM
) {

    private class Sample(val timeNanos: Long, val altitudeM: Float)

    private val samples = ArrayDeque<Sample>()

    /** Readings kept. */
    val size: Int get() = samples.size

    /** Adds a reading of [hPa] at [timeNanos]; a pressure that is not a positive number is ignored. */
    fun add(timeNanos: Long, hPa: Float) {
        if (!hPa.isFinite() || hPa <= 0f) return
        val altitude = toMeters(hPa)
        if (!altitude.isFinite()) return
        samples.addLast(Sample(timeNanos, altitude))
        val newest = samples.maxOf { it.timeNanos }
        while (samples.isNotEmpty() && newest - samples.first().timeNanos > historyNanos) samples.removeFirst()
    }

    /** Median altitude of the readings within ±[halfWindowNanos] of [timeNanos]; null without any. */
    fun altitudeAt(timeNanos: Long): Float? =
        samples.filter { abs(it.timeNanos - timeNanos) <= halfWindowNanos }
            .map { it.altitudeM.toDouble() }
            .median()
            ?.toFloat()

    /** Forgets every reading (a new run starts with none). */
    fun clear() {
        samples.clear()
    }

    companion object {
        const val DEFAULT_HISTORY_NANOS = 180_000_000_000L
        const val DEFAULT_HALF_WINDOW_NANOS = 2_000_000_000L

        /** Sea-level pressure of the standard atmosphere, hPa (`SensorManager.PRESSURE_STANDARD_ATMOSPHERE`). */
        const val STANDARD_PRESSURE_HPA = 1013.25f

        /** The formula of `SensorManager.getAltitude` against the standard atmosphere, for the JVM. */
        fun standardAltitudeM(hPa: Float): Float =
            44330f * (1f - (hPa / STANDARD_PRESSURE_HPA).pow(1f / 5.255f))
    }
}
