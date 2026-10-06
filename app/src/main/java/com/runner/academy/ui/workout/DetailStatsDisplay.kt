package com.runner.academy.ui.workout

import android.view.View
import com.runner.academy.R
import com.runner.academy.data.Workout
import com.runner.academy.data.WorkoutType
import com.runner.academy.util.MovingTimeDisplay
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Handles statistics display for workout detail screen.
 * Populates detail TextViews with distance, time, speed, pace, calories, etc.
 */
class DetailStatsDisplay(
    private val binding: com.runner.academy.databinding.FragmentWorkoutDetailBinding,
    private val context: android.content.Context,
    private val viewModel: WorkoutViewModel
) {

    fun displayWorkout(workout: Workout) {
        binding.apply {
            val dateFormat = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
            val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
            textViewDetailDate.text = dateFormat.format(workout.date)
            textViewDetailTime.text = timeFormat.format(workout.date)

            textViewDetailType.text = getWorkoutTypeDisplayName(workout.type)

            textViewDetailDistance.text = com.runner.academy.util.FormatUtils.formatDistance(workout.distance, context)
            // The time next to the pace is the moving one; the elapsed time gets its own tile
            // only when it differs (otherwise one "Time", as before)
            textViewDetailDuration.text = viewModel.formatDuration(workout.movingDuration)
            textViewDetailPace.text = viewModel.formatPace(workout.avgPace, context)
            val showsElapsed = MovingTimeDisplay.detailShowsElapsed(workout.duration, workout.movingDuration)
            textViewDetailDurationLabel.setText(
                if (showsElapsed) R.string.workout_moving_time_label else R.string.workout_time_label
            )
            layoutDetailThirdRow.visibility = if (showsElapsed) View.VISIBLE else View.GONE
            textViewDetailElapsed.text = viewModel.formatDuration(workout.duration)

            val avgSpeed = com.runner.academy.util.FormatUtils.calculateAverageSpeed(workout.distance, workout.movingDuration)
            textViewDetailAvgSpeed.text = com.runner.academy.util.FormatUtils.formatSpeed(avgSpeed, true, context)

            textViewDetailCalories.text = com.runner.academy.util.FormatUtils.formatCalories(workout.calories ?: 0, context)

            // No steps (no sensor or permission, an older or manual workout): no tile, not "—"
            val cadence = workout.avgCadence?.takeIf { it.isFinite() }?.roundToInt()
            layoutDetailCadence.visibility = if (cadence != null) View.VISIBLE else View.GONE
            if (cadence != null) {
                textViewDetailCadence.text = String.format(Locale.getDefault(), "%d", cadence)
                layoutDetailCadence.contentDescription = context.getString(
                    R.string.workout_details_cadence_a11y,
                    context.resources.getQuantityString(R.plurals.cadence_steps_per_minute, cadence, cadence)
                )
            }
        }
    }

    private fun getWorkoutTypeDisplayName(type: WorkoutType): String {
        return when (type) {
            WorkoutType.EASY_RUN -> context.getString(R.string.workout_type_easy_run)
            WorkoutType.TEMPO_RUN -> context.getString(R.string.workout_type_tempo_run)
            WorkoutType.INTERVAL_TRAINING -> context.getString(R.string.workout_type_interval_training)
            WorkoutType.LONG_RUN -> context.getString(R.string.workout_type_long_run)
            WorkoutType.RECOVERY_RUN -> context.getString(R.string.workout_type_recovery_run)
            WorkoutType.RACE -> context.getString(R.string.workout_type_competition)
        }
    }
}
