package com.runner.academy.service

/**
 * Pure step bookkeeping behind [StepTracker]: turns step-sensor readings into steps since the
 * workout start and a rolling cadence. No Android types, so it is unit-tested on the JVM.
 *
 * Two inputs are supported:
 * - [onCounterValue] for `TYPE_STEP_COUNTER`, a cumulative count since boot: the first value
 *   after [start] / a pause is only a baseline, later values add their delta. A value lower than
 *   the previous one means the counter restarted (reboot) and counts from zero.
 * - [onDetectedSteps] for `TYPE_STEP_DETECTOR`, one event per step.
 *
 * Steps that happen while paused are not counted: counter values keep moving the baseline.
 *
 * Cadence is steps per minute over the last [cadenceWindowNanos]; it is `null` while paused,
 * before start, or until [minCadenceSpanNanos] of data exist after start/resume, and falls to 0
 * when no steps arrive for a whole window (standing still).
 *
 * [stepsAt] answers the count at an earlier moment from a short history ([historyNanos]): GPS
 * fixes batched with the screen off arrive late, and each must carry the steps of its own time.
 *
 * Timestamps are one monotonic clock in nanoseconds (the caller uses `elapsedRealtimeNanos`).
 * Not thread-safe; [StepTracker] synchronises access.
 */
class StepCountAccumulator(
    private val cadenceWindowNanos: Long = DEFAULT_CADENCE_WINDOW_NANOS,
    private val minCadenceSpanNanos: Long = DEFAULT_MIN_CADENCE_SPAN_NANOS,
    private val historyNanos: Long = DEFAULT_HISTORY_NANOS
) {

    private class Sample(val timeNanos: Long, val steps: Int)

    private var started = false
    private var paused = false
    private var stepCount = 0
    private var lastCounterValue: Long? = null
    private val samples = ArrayDeque<Sample>()
    private val history = ArrayDeque<Sample>()

    /** Steps counted since [start], excluding pauses. */
    val steps: Int get() = stepCount

    val isPaused: Boolean get() = paused

    /**
     * Starts (or restarts) counting. [initialSteps] continues a restored workout; the cadence
     * window begins at [nowNanos].
     */
    fun start(nowNanos: Long, initialSteps: Int = 0) {
        started = true
        paused = false
        stepCount = initialSteps.coerceAtLeast(0)
        lastCounterValue = null
        samples.clear()
        samples.addLast(Sample(nowNanos, stepCount))
        history.clear()
        history.addLast(Sample(nowNanos, stepCount))
    }

    /**
     * Steps counted at [timeNanos]: the count after the last step event at or before it, the
     * oldest kept count for a time before the history, [steps] for now or later.
     */
    fun stepsAt(timeNanos: Long): Int {
        if (history.isEmpty() || timeNanos >= history.last().timeNanos) return stepCount
        var result = history.first().steps
        for (sample in history) {
            if (sample.timeNanos > timeNanos) break
            result = sample.steps
        }
        return result
    }

    fun onCounterValue(totalSinceBoot: Long, nowNanos: Long) {
        if (!started || totalSinceBoot < 0) return
        val previous = lastCounterValue
        lastCounterValue = totalSinceBoot
        if (previous == null || paused) return
        val delta = if (totalSinceBoot >= previous) totalSinceBoot - previous else totalSinceBoot
        addSteps(delta.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), nowNanos)
    }

    fun onDetectedSteps(count: Int, nowNanos: Long) {
        if (!started || paused || count <= 0) return
        addSteps(count, nowNanos)
    }

    fun pause() {
        if (!started) return
        paused = true
        samples.clear()
    }

    /** Resumes counting; the cadence window restarts at [nowNanos]. */
    fun resume(nowNanos: Long) {
        if (!started || !paused) return
        paused = false
        samples.clear()
        samples.addLast(Sample(nowNanos, stepCount))
    }

    /** Steps per minute over the recent window, or `null` when not known (see class docs). */
    fun cadence(nowNanos: Long): Float? {
        if (!started || paused || samples.isEmpty()) return null
        val windowStart = nowNanos - cadenceWindowNanos
        prune(windowStart)
        val first = samples.first()
        val (refTime, refSteps) = if (first.timeNanos <= windowStart) {
            // Steps change only at sample times, so the count at the window start is first.steps.
            windowStart to first.steps
        } else {
            first.timeNanos to first.steps
        }
        val span = nowNanos - refTime
        if (span < minCadenceSpanNanos) return null
        val counted = stepCount - refSteps
        return (counted * NANOS_PER_MINUTE / span).toFloat()
    }

    private fun addSteps(count: Int, nowNanos: Long) {
        if (count <= 0) return
        stepCount += count
        samples.addLast(Sample(nowNanos, stepCount))
        prune(nowNanos - cadenceWindowNanos)
        history.addLast(Sample(nowNanos, stepCount))
        while (history.size >= 2 && history[1].timeNanos <= nowNanos - historyNanos) history.removeFirst()
    }

    /** Keeps the newest sample at or before [windowStart] as the reference, drops older ones. */
    private fun prune(windowStart: Long) {
        while (samples.size >= 2 && samples[1].timeNanos <= windowStart) {
            samples.removeFirst()
        }
    }

    companion object {
        const val DEFAULT_CADENCE_WINDOW_NANOS = 12_000_000_000L
        const val DEFAULT_MIN_CADENCE_SPAN_NANOS = 5_000_000_000L
        /** Longer than a screen-off batch of fixes. */
        const val DEFAULT_HISTORY_NANOS = 180_000_000_000L
        private const val NANOS_PER_MINUTE = 60_000_000_000.0
    }
}
