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
        forEachCountedPair(points, pauses) { from, to, countedMs ->
            weighted += (from + to) / 2.0 * countedMs
            coveredMs += countedMs
        }
        if (coveredMs < MIN_COVERED_MS) return null
        return (weighted / coveredMs).roundToInt()
    }

    /**
     * Time in each of [zones]' buckets, ms: index 0 below Z1, 1..5 the zones. The pairs of
     * [average], each in the zone of its mean `(from + to) / 2` (whole beats, floored), so the
     * buckets sum up to the time the average covers. Null on the same coverage rule.
     */
    fun timeInZones(points: List<TrackPoint>, pauses: List<PauseInterval>?, zones: HrZones): LongArray? {
        val ms = LongArray(HrZones.COUNT + 1)
        forEachCountedPair(points, pauses) { from, to, countedMs ->
            ms[zones.zoneOf((from + to) / 2)] += countedMs
        }
        if (ms.sum() < MIN_COVERED_MS) return null
        return ms
    }

    /**
     * Pairs of neighbouring points that both carry a heart rate, at most [MAX_PAIR_MS] apart,
     * with the time left after their overlap with [pauses] of any kind (when there is some).
     */
    private inline fun forEachCountedPair(
        points: List<TrackPoint>,
        pauses: List<PauseInterval>?,
        action: (from: Int, to: Int, countedMs: Long) -> Unit
    ) {
        for (i in 1 until points.size) {
            val p = points[i - 1]
            val q = points[i]
            val from = p.heartRate ?: continue
            val to = q.heartRate ?: continue
            val spanMs = q.timestamp - p.timestamp
            if (spanMs <= 0L || spanMs > MAX_PAIR_MS) continue
            val countedMs = spanMs - TrackPauses.overlapMs(pauses, p.timestamp, q.timestamp)
            if (countedMs <= 0L) continue
            action(from, to, countedMs)
        }
    }

    /**
     * The highest median of [MAX_WINDOW] neighbouring values inside one [stretches] run: a
     * single spike (a dry strap reads 220 at the start) never reaches it, two in a row do, and
     * a window never joins values across a break, a pause or a missing value. Null without an
     * [average] (one coverage rule for both: a maximum nobody sees is not kept) or without a
     * run of [MAX_WINDOW] values.
     */
    fun max(points: List<TrackPoint>, pauses: List<PauseInterval>?): Int? {
        if (average(points, pauses) == null) return null
        return stretches(points, pauses)
            .flatMap { run -> run.mapNotNull { it.heartRate }.windowed(MAX_WINDOW) }
            .maxOfOrNull { it.sorted()[MAX_WINDOW / 2] }
    }

    /**
     * The track split where the heart rate is continuous: points with a value, outside pauses,
     * at most [MAX_PAIR_MS] apart and not across a pause. The chart draws these runs
     * ([TrackChartBuilder.buildHeartRateSeries]) and [max] takes its windows inside them.
     */
    fun stretches(points: List<TrackPoint>, pauses: List<PauseInterval>?): List<List<TrackPoint>> =
        TrackChartBuilder.buildRuns(
            points,
            pauses,
            hasValue = { it.heartRate != null },
            breakBetween = { prev, point -> point.timestamp - prev.timestamp > MAX_PAIR_MS }
        )
}
