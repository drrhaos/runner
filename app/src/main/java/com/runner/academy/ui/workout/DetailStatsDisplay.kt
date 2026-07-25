package com.runner.academy.ui.workout

import com.runner.academy.R
import com.runner.academy.data.Workout
import com.runner.academy.data.WorkoutType
import java.text.SimpleDateFormat
import java.util.Locale

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
            textViewDetailDuration.text = viewModel.formatDuration(workout.duration)
            textViewDetailPace.text = viewModel.formatPace(workout.avgPace)

            val avgSpeed = com.runner.academy.util.FormatUtils.calculateAverageSpeed(workout.distance, workout.duration)
            textViewDetailAvgSpeed.text = com.runner.academy.util.FormatUtils.formatSpeed(avgSpeed, true, context)

            textViewDetailCalories.text = com.runner.academy.util.FormatUtils.formatCalories(workout.calories ?: 0, context)
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
