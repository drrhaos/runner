package com.runner.academy.service

import com.runner.academy.data.GpsStatus
import com.runner.academy.data.LivePace
import com.runner.academy.util.StepDistanceEstimator

/**
 * The live pace: the last [WINDOW_MS] of moving time (the target's verdict asks for
 * [VERDICT_WINDOW_MS]), ticked once a second while the workout
 * runs. One per run, on the run's frozen [stride] (the one that bridges silences), null without
 * steps.
 *
 * - **GPS pace** — Δdistance over the moving time between the window's fixes, when no tick of
 *   the window (from its start on) had a stale GPS. So the jump of a closing bridge (the step
 *   distance a fix writes in after a silence) never falls into a GPS window.
 * - **Steps pace** — otherwise, when both ends of the window have steps: `stride.distanceMeters`
 *   of Δsteps (at the window's mean cadence) over Δmoving; [LivePace.approximate]. From the steps
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
        val steps: Int?
    )

    private val samples = ArrayDeque<Sample>(CAPACITY + 1)
    /** The moving time of the latest tick without fresh GPS since the last [restart]. */
    private var lastStaleMovingMs: Long? = null
    /**
     * The moving time of the start or the last [restart]: no fix time reaches before it, so a fix
     * taken in a pause does not lend the window the paused time.
     */
    private var floorMovingMs = 0L

    /**
     * @param distanceM the session's distance.
     * @param steps the step tracker's count, null without one.
     * @param gpsFresh see [isGpsFresh].
     * @param gpsAgeMs how old the last good fix is: the distance is measured to its moment.
     */
    fun onTick(movingMs: Long, distanceM: Float, steps: Int?, gpsFresh: Boolean, gpsAgeMs: Long = 0L) {
        val last = samples.lastOrNull()
        when {
            last == null -> Unit
            movingMs < last.movingMs -> restart(atMovingMs = movingMs)
            movingMs == last.movingMs -> samples.removeLast()
        }
        if (!gpsFresh) lastStaleMovingMs = movingMs
        // A negative age (a clock stepped back) counts as none; never before the restart
        val fixMovingMs = if (gpsFresh) maxOf(movingMs - gpsAgeMs.coerceAtLeast(0L), floorMovingMs) else movingMs
        samples.addLast(Sample(movingMs, fixMovingMs, distanceM, steps))
        while (samples.size > CAPACITY) samples.removeFirst()
    }

    /**
     * The pace over `[max(now − windowMs, sinceMovingMs), now]` of moving time; [sinceMovingMs]
     * cuts the window at a segment's start, so the pace before it does not count. The tile's
     * window is [WINDOW_MS]; the target's verdict asks for [VERDICT_WINDOW_MS]. GPS or steps is
     * decided over the window asked for, from its own start.
     */
    fun pace(sinceMovingMs: Long = 0L, windowMs: Long = WINDOW_MS): LivePace? {
        val end = samples.lastOrNull() ?: return null
        val from = maxOf(end.movingMs - windowMs.coerceIn(MIN_SPAN_MS, MAX_WINDOW_MS), sinceMovingMs)
        val start = samples.firstOrNull { it.movingMs >= from } ?: return null
        if (end.movingMs - start.movingMs < MIN_SPAN_MS) return null

        // Against this window's own start: a stale tick before a segment's start does not count
        val stale = lastStaleMovingMs
        val gps = stale == null || stale < start.movingMs
        val (meters, ms) = if (gps) {
            (end.distanceM - start.distanceM) to (end.fixMovingMs - start.fixMovingMs)
        } else {
            val estimator = stride ?: return null
            val fromSteps = start.steps ?: return null
            val toSteps = end.steps ?: return null
            if (toSteps < fromSteps) return null
            val spanMs = end.movingMs - start.movingMs
            // The window's mean cadence, like the bridges (TrackFilter.stepsMeters)
            val cadence = (toSteps - fromSteps) * 60_000f / spanMs
            estimator.distanceMeters(toSteps - fromSteps, cadence) to spanMs
        }
        if (meters < MIN_METERS || ms <= 0L) return null
        val minPerKm = (ms / 60_000f) / (meters / 1_000f)
        if (!minPerKm.isFinite() || minPerKm > MAX_PACE_MIN_PER_KM) return null
        return LivePace(minPerKm, approximate = !gps)
    }

    /**
     * Moving time since the start or the last [restart] (a resume, a restore) to the latest tick:
     * under [MIN_SPAN_MS] no pace is possible yet, which is "pending", not "no data".
     */
    val sinceRestartMs: Long
        get() = ((samples.lastOrNull()?.movingMs ?: floorMovingMs) - floorMovingMs).coerceAtLeast(0L)

    /**
     * Forgets the window: no pace for the next [MIN_SPAN_MS]. [atMovingMs] is the moving time of
     * the restart (a resume, an auto-pause, a restore): no fix time counts before it.
     */
    fun restart(atMovingMs: Long = 0L) {
        samples.clear()
        lastStaleMovingMs = null
        floorMovingMs = atMovingMs
    }

    companion object {
        /** The current-pace tile's window. */
        const val WINDOW_MS = 30_000L
        /**
         * The segment target's verdict: twice the tile's, so the GPS noise (~5 s per km over
         * 30 s, correlated over ~20 s) swings it half as much and an even run gets no false hint.
         */
        const val VERDICT_WINDOW_MS = 60_000L
        const val MIN_SPAN_MS = 15_000L
        const val MIN_METERS = 10f
        const val MAX_PACE_MIN_PER_KM = 20f
        /** The longest window a caller may ask for. */
        private const val MAX_WINDOW_MS = VERDICT_WINDOW_MS
        /** A sample a second: the longest window and some slack for late ticks. */
        private const val CAPACITY = 70
        private const val FRESH_MIN_MS = 5_000L

        /**
         * GPS counts for the pace: the last good fix is at most `max(5 s, 2 × the request's
         * interval)` old ([fixAgeMs], null: none yet; negative: the wall clock stepped back, not
         * fresh), no false-signal episode is open and the
         * status is neither [GpsStatus.LOST] nor [GpsStatus.UNRELIABLE].
         */
        fun isGpsFresh(fixAgeMs: Long?, requestIntervalMs: Long, inFalseSignal: Boolean, status: GpsStatus): Boolean =
            fixAgeMs != null &&
                fixAgeMs in 0..maxOf(FRESH_MIN_MS, 2 * requestIntervalMs) &&
                !inFalseSignal &&
                status != GpsStatus.LOST &&
                status != GpsStatus.UNRELIABLE
    }
}
