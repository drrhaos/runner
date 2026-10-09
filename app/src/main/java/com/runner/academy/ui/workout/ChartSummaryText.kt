package com.runner.academy.ui.workout

import android.content.Context
import com.runner.academy.R
import com.runner.academy.data.SegmentKind
import com.runner.academy.data.WorkoutTemplateSegment
import com.runner.academy.data.localizedTitle
import com.runner.academy.util.SegmentStats
import com.runner.academy.util.SpeedPaceCalculator

/**
 * The pace splits a chart summary reads: the average over all of them and the bars (0-based
 * indices into the list) of the lowest and the highest pace. Bars without a pace are skipped.
 */
data class SegmentSummary(val averagePace: Float, val fastestIndex: Int, val slowestIndex: Int) {
    companion object {
        /**
         * Null when no segment has a pace. The average is per km, or per mile without [metric].
         * Only the [rated] bars compete for fastest and slowest — all of them when none of the
         * rated ones has a pace.
         */
        fun of(segments: List<SegmentStats>, metric: Boolean, rated: (Int) -> Boolean = { true }): SegmentSummary? {
            val timed = segments.withIndex().filter { (_, segment) ->
                segment.paceMinPerUnit.isFinite() && segment.paceMinPerUnit > 0f
            }
            if (timed.isEmpty()) return null
            // The whole time over the whole distance, as the tile's average; not a mean of paces
            val averagePace = SpeedPaceCalculator.segmentPaceMetric(
                timed.sumOf { it.value.durationMs },
                timed.sumOf { it.value.distanceKm.toDouble() }.toFloat(),
                metric
            )
            val candidates = timed.filter { rated(it.index) }.ifEmpty { timed }
            return SegmentSummary(
                averagePace = averagePace,
                fastestIndex = candidates.minBy { it.value.paceMinPerUnit }.index,
                slowestIndex = candidates.maxBy { it.value.paceMinPerUnit }.index
            )
        }
    }
}

/**
 * Summaries of the detail charts for TalkBack, which cannot see inside MPAndroidChart: the
 * `contentDescription` of the chart view. Both read the distance or plan splits of the track.
 */
object ChartSummaryText {

    /** Warm-up, recovery and cool-down are not what "fastest" and "slowest" are about. */
    private val unratedKinds = setOf(SegmentKind.WARMUP, SegmentKind.RECOVERY, SegmentKind.COOLDOWN)

    /** "Pace chart: average …, best …, worst …"; null without a split with a pace. */
    fun paceA11y(context: Context, segments: List<SegmentStats>, metric: Boolean): String? {
        val summary = SegmentSummary.of(segments, metric) ?: return null
        return context.getString(
            R.string.chart_pace_a11y,
            MetricSpeech.pace(context, summary.averagePace, metric),
            MetricSpeech.pace(context, segments[summary.fastestIndex].paceMinPerUnit, metric),
            MetricSpeech.pace(context, segments[summary.slowestIndex].paceMinPerUnit, metric)
        )
    }

    /**
     * The bar chart: how many bars, the fastest and the slowest one in what the bars show — pace,
     * or speed with [speed]. A bar is "segment N" as drawn; with an interval [plan] it is also
     * named by its title, as on the axis, and only the work intervals are rated. Null without a
     * bar with a pace.
     */
    fun segmentsA11y(
        context: Context,
        segments: List<SegmentStats>,
        metric: Boolean,
        speed: Boolean,
        plan: List<WorkoutTemplateSegment> = emptyList()
    ): String? {
        val summary = SegmentSummary.of(segments, metric) { index ->
            plan.getOrNull(index)?.kind !in unratedKinds
        } ?: return null
        fun value(index: Int): String {
            val segment = segments[index]
            return if (speed) {
                MetricSpeech.speed(context, segment.speedDisplay, metric)
            } else {
                MetricSpeech.pace(context, segment.paceMinPerUnit, metric)
            }
        }
        fun name(index: Int): String {
            val number = context.getString(R.string.chart_segment_n_a11y, index + 1)
            val title = plan.getOrNull(index)?.localizedTitle(context)?.takeIf { it.isNotBlank() }
                ?: return number
            return context.getString(R.string.chart_segment_titled_a11y, title, number)
        }
        if (segments.size == 1) {
            return context.getString(R.string.chart_segments_single_a11y, value(0))
        }
        return context.getString(
            R.string.chart_segments_a11y,
            context.resources.getQuantityString(R.plurals.chart_segments_count, segments.size, segments.size),
            name(summary.fastestIndex),
            value(summary.fastestIndex),
            name(summary.slowestIndex),
            value(summary.slowestIndex)
        )
    }
}
