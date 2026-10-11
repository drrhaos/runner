package com.runner.academy.util

import com.runner.academy.data.PauseInterval
import com.runner.academy.data.TrackPoint
import kotlin.math.roundToInt

/** What counts as a heart rate at all, wherever a value comes from (a sensor, a GPX file). */
object HeartRates {

    const val MIN_BPM = 25
    const val MAX_BPM = 250

    /** [bpm] when it is a plausible heart rate, else null: 0 and values outside 25..250 are no data. */
    fun valid(bpm: Int?): Int? = bpm?.takeIf { it in MIN_BPM..MAX_BPM }
}

/**
 * The average and maximum heart rate of a saved track
 * ([com.runner.academy.data.Workout.avgHeartRate], [com.runner.academy.data.Workout.maxHeartRate]).
 */
object TrackHeartRate {

    /** A longer step between two fixes is a break: the heart rate across it is unknown. */
    const val MAX_PAIR_MS = 10_000L

    /** Less covered time than this gives no average: too short to mean anything. */
    private const val MIN_COVERED_MS = 60_000L

    /** Known values in a row the maximum is the median of: one spike never reaches it. */
    private const val MAX_WINDOW = 3

    /**
     * Time-weighted average over pairs of neighbouring points that both carry a heart rate and
     * are at most [MAX_PAIR_MS] apart: a pair counts the mean of its two values for its time
     * minus its overlap with [pauses] of any kind (a stand on an auto-pause does not lower it).
     * Points without a value (no contact, no sensor) count in neither sum.
     * Null: no such pairs or under a minute of their time.
     */
    fun average(points: List<TrackPoint>, pauses: List<PauseInterval>?): Int? {
        var weighted = 0.0
        var coveredMs = 0L
        for (i in 1 until points.size) {
            val p = points[i - 1]
            val q = points[i]
            val from = p.heartRate ?: continue
            val to = q.heartRate ?: continue
            val spanMs = q.timestamp - p.timestamp
            if (spanMs <= 0L || spanMs > MAX_PAIR_MS) continue
            val countedMs = spanMs - TrackPauses.overlapMs(pauses, p.timestamp, q.timestamp)
            if (countedMs <= 0L) continue
            weighted += (from + to) / 2.0 * countedMs
            coveredMs += countedMs
        }
        if (coveredMs < MIN_COVERED_MS) return null
        return (weighted / coveredMs).roundToInt()
    }

    /**
     * The highest median of [MAX_WINDOW] neighbouring known values (points without one are
     * skipped): a single spike (a dry strap reads 220 at the start) never reaches it, two in a
     * row do. Null with fewer than [MAX_WINDOW] known values.
     */
    fun max(points: List<TrackPoint>): Int? {
        val known = points.mapNotNull { it.heartRate }
        if (known.size < MAX_WINDOW) return null
        return known.windowed(MAX_WINDOW).maxOf { it.sorted()[MAX_WINDOW / 2] }
    }
}
