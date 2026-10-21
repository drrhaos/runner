package com.runner.academy.util

import com.runner.academy.data.PauseInterval
import com.runner.academy.data.PauseKind
import com.runner.academy.data.TrackPoint

/** The average cadence of a saved track ([com.runner.academy.data.Workout.avgCadence]). */
object TrackCadence {

    /** Less moving time with steps than this gives no average: too short to mean anything. */
    private const val MIN_MOVING_MS = 60_000L
    private const val MS_PER_MINUTE = 60_000f

    /**
     * Steps per minute of moving time, over pairs of neighbouring points that both carry
     * cumulative steps (a counter that went back is skipped). A pair counts its time minus its
     * overlap with [pauses] of any kind, and the same share of its steps:
     * - the step tracker drops steps on a manual pause, so a pair's steps were counted over
     *   its time outside manual pauses;
     * - the counter keeps going on an auto-pause (marching on the spot), so of those steps only
     *   the share of the moving time is kept, as if they were even over the counted time.
     * Points are seconds apart, so the even split inside a pair is exact enough.
     *
     * Standing without an auto-pause counts; steps before the first fix (no time) and
     * stretches without steps (no sensor, a revoked permission) count in neither sum.
     * Null: no such pairs, under a minute of their moving time, or no step counted at all
     * (a silent sensor is no data, not a cadence of zero).
     */
    fun average(points: List<TrackPoint>, pauses: List<PauseInterval>?): Float? {
        val manualPauses = pauses?.filter { it.kind == PauseKind.MANUAL }
        var steps = 0.0
        var movingMs = 0L
        for (i in 1 until points.size) {
            val p = points[i - 1]
            val q = points[i]
            val from = p.steps ?: continue
            val to = q.steps ?: continue
            if (to < from) continue
            val spanMs = q.timestamp - p.timestamp
            val countedMs = spanMs - TrackPauses.overlapMs(manualPauses, p.timestamp, q.timestamp)
            if (countedMs <= 0L) continue
            val pairMovingMs = (spanMs - TrackPauses.overlapMs(pauses, p.timestamp, q.timestamp)).coerceAtLeast(0L)
            steps += (to - from).toDouble() * pairMovingMs / countedMs
            movingMs += pairMovingMs
        }
        if (movingMs < MIN_MOVING_MS || steps <= 0.0) return null
        return (steps / (movingMs / MS_PER_MINUTE)).toFloat()
    }
}
