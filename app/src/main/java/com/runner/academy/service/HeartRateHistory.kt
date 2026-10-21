package com.runner.academy.service

import com.runner.academy.sensor.HeartRateSample

/**
 * Pure bookkeeping behind [HeartRateTracker]: the samples of the last [historyNanos] (2 min),
 * so a GPS fix that arrives late (batched with the screen off) still gets the heart rate of its
 * own moment, like `PressureAltitudeHistory.altitudeAt`.
 *
 * [bpmAt] takes the newest sample in `[t − 5 s, t]`, else the oldest in `(t, t + 1 s]`; no
 * interpolation. A "no contact" sample there is no value: the strap was off at that moment.
 * The window is 5 s, not 3: a sensor notifies about once a second and a weak radio loses two
 * or three in a row.
 *
 * Timestamps are `elapsedRealtimeNanos`. Not thread-safe; the tracker synchronises access.
 */
class HeartRateHistory(private val historyNanos: Long = DEFAULT_HISTORY_NANOS) {

    /** Ordered by time, oldest first. */
    private val samples = ArrayDeque<HeartRateSample>()

    val size: Int get() = samples.size

    fun add(sample: HeartRateSample) {
        // Samples come in order; a late one (two threads) is put in its place
        var index = samples.size
        while (index > 0 && samples[index - 1].elapsedNanos > sample.elapsedNanos) index--
        samples.add(index, sample)
        val newest = samples.last().elapsedNanos
        while (samples.isNotEmpty() && newest - samples.first().elapsedNanos > historyNanos) samples.removeFirst()
    }

    /** The heart rate at [elapsedNanos] (a fix's time), null without a usable sample around it. */
    fun bpmAt(elapsedNanos: Long): Int? {
        val before = samples.lastOrNull {
            it.elapsedNanos <= elapsedNanos && elapsedNanos - it.elapsedNanos <= BEFORE_NANOS
        }
        if (before != null) return before.bpm
        return samples.firstOrNull {
            it.elapsedNanos > elapsedNanos && it.elapsedNanos - elapsedNanos <= AFTER_NANOS
        }?.bpm
    }

    /** The newest sample, "no contact" included; null before the first. */
    fun latest(): HeartRateSample? = samples.lastOrNull()

    fun clear() {
        samples.clear()
    }

    companion object {
        const val DEFAULT_HISTORY_NANOS = 120_000_000_000L
        const val BEFORE_NANOS = 5_000_000_000L
        const val AFTER_NANOS = 1_000_000_000L
    }
}
