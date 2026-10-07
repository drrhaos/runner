package com.runner.academy.ui.workout

import android.content.Context
import com.runner.academy.R
import com.runner.academy.util.WorkoutDerivation
import kotlin.math.roundToInt

/** Cadence for TalkBack: "spm" would be read letter by letter. */
object CadenceText {

    /** "172 steps per minute", declined. */
    fun stepsPerMinute(context: Context, spm: Int): String =
        context.resources.getQuantityString(R.plurals.cadence_steps_per_minute, spm, spm)

    /** "Average cadence 172 steps per minute". */
    fun averageA11y(context: Context, spm: Int): String =
        context.getString(R.string.workout_details_cadence_a11y, stepsPerMinute(context, spm))
}

/**
 * What the details show for cadence — the tile and the chart title alike, from the one stored
 * [com.runner.academy.data.Workout.avgCadence], so they always show the same number.
 */
sealed interface CadenceDisplay {
    /** The stored average, steps/min. */
    data class Value(val spm: Int) : CadenceDisplay

    /** Not computed yet (the background pass is behind) but the track has steps: "—". */
    data object Pending : CadenceDisplay

    /** No cadence: the tile and the chart card are hidden. */
    data object None : CadenceDisplay

    companion object {
        fun of(avgCadence: Float?, metricsVersion: Int, trackHasSteps: Boolean): CadenceDisplay {
            val stored = avgCadence?.takeIf { it.isFinite() }
            return when {
                stored != null -> Value(stored.roundToInt())
                WorkoutDerivation.isPending(metricsVersion, trackHasSteps) -> Pending
                else -> None
            }
        }
    }
}
