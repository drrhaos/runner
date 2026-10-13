package com.runner.academy.voice

import com.runner.academy.sensor.HeartRateDisplay
import kotlin.math.roundToInt

/**
 * When the periodic summary speaks and with which values. Ticked once a second while the
 * workout runs (not on a manual pause); the marks are multiples of the interval in distance or
 * in moving time.
 *
 * - [resume] puts the next mark after the current position: no summary for the past, after a
 *   process restore as after a change of the interval mid-run.
 * - A mark reached during an auto-pause waits for the run to go on.
 * - The split pace is Δmoving / Δdistance since the previous summary (or the resume); the
 *   split heart rate averages the [HeartRateDisplay.Freshness.FRESH] values of the moving ticks
 *   since then, left out when they cover under half of them. Both carry the interval their
 *   stretch really covered; after a resume or a change of the interval it is partial (null).
 */
class SummaryClock {

    /** The interval [nextMark] belongs to; null before the first tick or [resume]. */
    private var markedFor: SummaryEvery? = null
    /** Metres or moving milliseconds, by [markedFor]. */
    private var nextMark = 0L
    /** The position of the last [resume]: the next tick marks from there, not from its own. */
    private var pendingResume: Pair<Long, Float>? = null

    /** Ticked or resumed at least once: a later change of the interval leaves a partial split. */
    private var started = false

    private var splitMovingMs = 0L
    private var splitDistanceM = 0f
    /** The interval the current split covers in full; null: a partial one. */
    private var splitSpan: SummaryEvery? = null
    private var hrSum = 0L
    private var hrCount = 0
    private var movingTicks = 0

    /** Continues from this position (a restore): the next summary is the next mark after it. */
    fun resume(movingMs: Long, distanceM: Float) {
        markedFor = null
        started = true
        startSplit(movingMs, distanceM, span = null)
        pendingResume = movingMs to distanceM
    }

    fun onTick(
        movingMs: Long,
        distanceM: Float,
        autoPaused: Boolean,
        avgPace: Float,
        heartRate: HeartRateDisplay?,
        profile: VoiceProfile
    ): VoiceLine.Summary? {
        if (!autoPaused) {
            movingTicks++
            heartRate?.takeIf { it.isFresh }?.bpm?.let { hrSum += it; hrCount++ }
        }
        val every = profile.activeSummary
        if (every != markedFor) {
            val (fromMs, fromM) = pendingResume ?: (movingMs to distanceM)
            markedFor = every
            nextMark = markAfter(every, fromMs, fromM)
            // From the start of the run the split is whole; after a change it is not
            splitSpan = if (started) null else every
            started = true
        }
        pendingResume = null
        if (every == SummaryEvery.OFF || autoPaused) return null
        if (positionOf(every, movingMs, distanceM) < nextMark) return null

        val parts = profile.orderedMetrics.mapNotNull { metric ->
            partOf(metric, movingMs, distanceM, avgPace, heartRate)
        }
        nextMark = markAfter(every, movingMs, distanceM)
        startSplit(movingMs, distanceM, span = every)
        return VoiceLine.Summary(parts)
    }

    private fun partOf(
        metric: SummaryMetric,
        movingMs: Long,
        distanceM: Float,
        avgPace: Float,
        heartRate: HeartRateDisplay?
    ): SummaryPart? = when (metric) {
        SummaryMetric.DISTANCE -> SummaryPart.Distance(distanceM)
        SummaryMetric.TIME -> SummaryPart.Time(movingMs)
        SummaryMetric.AVG_PACE -> avgPace.takeIf { it.isFinite() && it > 0f }?.let { SummaryPart.AvgPace(it) }
        SummaryMetric.SPLIT_PACE -> splitPace(movingMs, distanceM)?.let { SummaryPart.SplitPace(it, splitSpan) }
        SummaryMetric.SPLIT_HR -> splitHeartRate()?.let { SummaryPart.SplitHr(it, splitSpan) }
        SummaryMetric.CURRENT_HR -> heartRate
            ?.takeIf { it.freshness != HeartRateDisplay.Freshness.NONE }
            ?.bpm
            ?.let { SummaryPart.CurrentHr(it) }
    }

    private fun splitPace(movingMs: Long, distanceM: Float): Float? {
        val meters = distanceM - splitDistanceM
        if (meters < MIN_SPLIT_M) return null
        val pace = ((movingMs - splitMovingMs) / 60_000f) / (meters / 1_000f)
        return pace.takeIf { it.isFinite() && it > 0f }
    }

    private fun splitHeartRate(): Int? {
        if (hrCount == 0 || hrCount * 2 < movingTicks) return null
        return (hrSum.toFloat() / hrCount).roundToInt()
    }

    private fun startSplit(movingMs: Long, distanceM: Float, span: SummaryEvery?) {
        splitMovingMs = movingMs
        splitDistanceM = distanceM
        splitSpan = span
        hrSum = 0L
        hrCount = 0
        movingTicks = 0
    }

    private fun positionOf(every: SummaryEvery, movingMs: Long, distanceM: Float): Long =
        if (every.meters != null) distanceM.toLong() else movingMs

    private fun markAfter(every: SummaryEvery, movingMs: Long, distanceM: Float): Long {
        val step = every.meters?.toLong() ?: every.minutes?.let { it * 60_000L } ?: return Long.MAX_VALUE
        return (positionOf(every, movingMs, distanceM) / step + 1) * step
    }

    private companion object {
        /** Shorter splits (a summary right after a resume) have no meaningful pace. */
        const val MIN_SPLIT_M = 10f
    }
}
