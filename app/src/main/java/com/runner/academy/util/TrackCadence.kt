package com.runner.academy.util

import com.runner.academy.data.PauseInterval
import com.runner.academy.data.TrackPoint

/** The average cadence of a saved track ([com.runner.academy.data.Workout.avgCadence]). */
object TrackCadence {

    /** Less moving time with steps than this gives no average: too short to mean anything. */
    private const val MIN_MOVING_MS = 60_000L
    private const val MS_PER_MINUTE = 60_000f

    /**
     * Steps per minute of moving time, over pairs of neighbouring points that both carry
     * cumulative steps (a counter that went back is skipped); the time of a pair minus its
     * overlap with [pauses] of any kind. Standing without an auto-pause counts; steps before
     * the first fix (no time) and stretches without steps (no sensor, a revoked permission)
     * count in neither sum. Null: no such pairs or under a minute of their moving time.
     */
    fun average(points: List<TrackPoint>, pauses: List<PauseInterval>?): Float? {
        var steps = 0L
        var movingMs = 0L
        for (i in 1 until points.size) {
            val p = points[i - 1]
            val q = points[i]
            val from = p.steps ?: continue
            val to = q.steps ?: continue
            if (to < from) continue
            steps += to - from
            movingMs += (q.timestamp - p.timestamp - TrackPauses.overlapMs(pauses, p.timestamp, q.timestamp))
                .coerceAtLeast(0L)
        }
        if (movingMs < MIN_MOVING_MS) return null
        return steps / (movingMs / MS_PER_MINUTE)
    }
}
