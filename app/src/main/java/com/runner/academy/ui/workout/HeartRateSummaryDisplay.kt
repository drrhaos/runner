package com.runner.academy.ui.workout

import android.content.Context
import com.runner.academy.R
import com.runner.academy.util.WorkoutDerivation

/** Heart rate for TalkBack: "bpm" would be read letter by letter. */
object HeartRateText {

    /** "152 beats per minute", declined. */
    fun beatsPerMinute(context: Context, bpm: Int): String =
        context.resources.getQuantityString(R.plurals.heart_rate_beats_per_minute, bpm, bpm)

    /** "Average heart rate 152 beats per minute". */
    fun averageA11y(context: Context, bpm: Int): String =
        context.getString(R.string.workout_details_heart_rate_a11y, beatsPerMinute(context, bpm))

    /** "Average heart rate 152 beats per minute, maximum 178 beats per minute"; without a maximum, the average. */
    fun summaryA11y(context: Context, avgBpm: Int, maxBpm: Int?): String =
        if (maxBpm == null) {
            averageA11y(context, avgBpm)
        } else {
            context.getString(
                R.string.chart_heart_rate_summary_a11y,
                beatsPerMinute(context, avgBpm),
                beatsPerMinute(context, maxBpm)
            )
        }
}

/**
 * What the details show for the heart rate of a saved workout — the tile and the chart card
 * alike, from the stored [com.runner.academy.data.Workout.avgHeartRate] and
 * [com.runner.academy.data.Workout.maxHeartRate], so they always show the same numbers.
 */
sealed interface HeartRateSummaryDisplay {
    /** The stored average and maximum, beats per minute (the maximum may be unknown). */
    data class Value(val avgBpm: Int, val maxBpm: Int?) : HeartRateSummaryDisplay

    /** Not computed yet (the background pass is behind) but the track has heart rate: "—". */
    data object Pending : HeartRateSummaryDisplay

    /** No heart rate: the tile is hidden. */
    data object None : HeartRateSummaryDisplay

    companion object {
        fun of(avgHeartRate: Int?, maxHeartRate: Int?, metricsVersion: Int, trackHasHeartRate: Boolean): HeartRateSummaryDisplay =
            when {
                avgHeartRate != null -> Value(avgHeartRate, maxHeartRate)
                WorkoutDerivation.isPending(metricsVersion, trackHasHeartRate) -> Pending
                else -> None
            }
    }
}
