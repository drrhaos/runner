package com.runner.academy.service

import com.runner.academy.data.GpsStatus
import com.runner.academy.data.LivePace
import com.runner.academy.util.StepDistanceEstimator

/**
 * The live pace: the last [WINDOW_MS] of moving time, ticked once a second while the workout
 * runs. One per run, on the run's frozen [stride] (the one that bridges silences), null without
 * steps.
 *
 * - **GPS pace** — Δdistance over the moving time between the window's fixes, when no tick in the
 *   last [WINDOW_MS] had a stale GPS. So the jump of a closing bridge (the step distance a fix
 *   writes in after a silence) never falls into a GPS window.
 * - **Steps pace** — otherwise, when both ends of the window have steps: `stride.distanceMeters`
 *   of Δsteps (at the last tick's cadence) over Δmoving; [LivePace.approximate]. From the steps
 *   themselves, not from the session's distance, which stands for 20 s in a silence and then
 *   jumps by 25 m.
 * - **None** — a window under [MIN_SPAN_MS], under [MIN_METERS] (standing) or slower than
 *   [MAX_PACE_MIN_PER_KM].
 *
 * The caller [restart]s it on a manual resume, while auto-paused and on a restore, so the first
 * [MIN_SPAN_MS] after each start have no pace and an auto-pause's GPS drift never enters a window.
 * A moving clock that goes back (a backdated auto-pause) starts it over too.
 */
class LivePaceTracker(private val stride: StepDistanceEstimator?) {

    /** [fixMovingMs]: the moving time of the fix behind [distanceM] (the tick's minus the fix's age). */
    private class Sample(
        val movingMs: Long,
        val fixMovingMs: Long,
        val distanceM: Float,
        val steps: Int?,
        val cadence: Float?
    )

    private val samples = ArrayDeque<Sample>(CAPACITY + 1)
    /** The moving time of the latest tick without fresh GPS since the last [restart]. */
    private var lastStaleMovingMs: Long? = null

    /**
     * @param distanceM the session's distance.
     * @param steps the step tracker's count, null without one; [cadence] its steps/min.
     * @param gpsFresh see [isGpsFresh].
     * @param gpsAgeMs how old the last good fix is: the distance is measured to its moment.
     */
    fun onTick(movingMs: Long, distanceM: Float, steps: Int?, cadence: Float?, gpsFresh: Boolean, gpsAgeMs: Long = 0L) {
        val last = samples.lastOrNull()
        when {
            last == null -> Unit
            movingMs < last.movingMs -> restart()
            movingMs == last.movingMs -> samples.removeLast()
        }
        if (!gpsFresh) lastStaleMovingMs = movingMs
        val age = if (gpsFresh) gpsAgeMs.coerceIn(0L, movingMs) else 0L
        samples.addLast(Sample(movingMs, movingMs - age, distanceM, steps, cadence))
        while (samples.size > CAPACITY) samples.removeFirst()
    }

    /**
     * The pace over `[max(now − 30 s, sinceMovingMs), now]` of moving time; [sinceMovingMs] cuts
     * the window at a segment's start, so the pace before it does not count.
     */
    fun pace(sinceMovingMs: Long = 0L): LivePace? {
        val end = samples.lastOrNull() ?: return null
        val from = maxOf(end.movingMs - WINDOW_MS, sinceMovingMs)
        val start = samples.firstOrNull { it.movingMs >= from } ?: return null
        if (end.movingMs - start.movingMs < MIN_SPAN_MS) return null

        val stale = lastStaleMovingMs
        val gps = stale == null || stale < end.movingMs - WINDOW_MS
        val (meters, ms) = if (gps) {
            (end.distanceM - start.distanceM) to (end.fixMovingMs - start.fixMovingMs)
        } else {
            val estimator = stride ?: return null
            val fromSteps = start.steps ?: return null
            val toSteps = end.steps ?: return null
            if (toSteps < fromSteps) return null
            estimator.distanceMeters(toSteps - fromSteps, end.cadence) to (end.movingMs - start.movingMs)
        }
        if (meters < MIN_METERS || ms <= 0L) return null
        val minPerKm = (ms / 60_000f) / (meters / 1_000f)
        if (!minPerKm.isFinite() || minPerKm > MAX_PACE_MIN_PER_KM) return null
        return LivePace(minPerKm, approximate = !gps)
    }

    /** Forgets the window: no pace for the next [MIN_SPAN_MS]. */
    fun restart() {
        samples.clear()
        lastStaleMovingMs = null
    }

    companion object {
        const val WINDOW_MS = 30_000L
        const val MIN_SPAN_MS = 15_000L
        const val MIN_METERS = 10f
        const val MAX_PACE_MIN_PER_KM = 20f
        /** A sample a second: the window and some slack for late ticks. */
        private const val CAPACITY = 40
        private const val FRESH_MIN_MS = 5_000L

        /**
         * GPS counts for the pace: the last good fix is at most `max(5 s, 2 × the request's
         * interval)` old ([fixAgeMs], null: none yet), no false-signal episode is open and the
         * status is neither [GpsStatus.LOST] nor [GpsStatus.UNRELIABLE].
         */
        fun isGpsFresh(fixAgeMs: Long?, requestIntervalMs: Long, inFalseSignal: Boolean, status: GpsStatus): Boolean =
            fixAgeMs != null &&
                fixAgeMs <= maxOf(FRESH_MIN_MS, 2 * requestIntervalMs) &&
                !inFalseSignal &&
                status != GpsStatus.LOST &&
                status != GpsStatus.UNRELIABLE
    }
}
