package com.example.runner.ui.tracking

import android.view.View
import android.widget.ImageButton
import android.widget.Spinner
import android.widget.TextView
import com.example.runner.R
import com.example.runner.data.GpsStatus
import com.example.runner.data.WorkoutSession
import com.example.runner.data.WorkoutState

/**
 * Manages real-time metrics display and button state transitions on the workout tracking screen.
 *
 * Responsibilities:
 * - Updating time, distance, speed, pace TextViews
 * - Button state transitions (start/pause/stop) based on WorkoutState
 * - Formatting and displaying workout statistics
 */
class MetricsDisplayManager(
    private val views: Views,
    private val viewModel: WorkoutTrackingViewModel
) {

    data class Views(
        val textViewWorkoutTime: TextView,
        val textViewWorkoutDistance: TextView,
        val textViewWorkoutTimeExpanded: TextView,
        val textViewWorkoutDistanceExpanded: TextView,
        val textViewWorkoutPace: TextView,
        val textViewWorkoutHeartRate: TextView,
        val textViewAvgSpeed: TextView,
        val textViewCurrentPace: TextView,
        val textViewCaloriesBurned: TextView,
        val spinnerWorkoutType: Spinner,
        val buttonStart: ImageButton,
        val buttonPause: ImageButton,
        val buttonStop: ImageButton,
    )

    fun updateMetrics(session: WorkoutSession) {
        val formattedTime = viewModel.formatTime(session.currentTime)
        val formattedDistance = String.format("%.2f", session.distance)

        views.textViewWorkoutTime.text = formattedTime
        views.textViewWorkoutDistance.text = formattedDistance
        views.textViewWorkoutTimeExpanded.text = formattedTime
        views.textViewWorkoutDistanceExpanded.text = formattedDistance
        views.textViewWorkoutPace.text = stripUnit(viewModel.formatPace(session.avgPace))
        views.textViewWorkoutHeartRate.text = if (session.heartRate > 0) session.heartRate.toString() else "--"
        views.textViewAvgSpeed.text = stripUnit(viewModel.formatSpeed(session.avgSpeed))
        views.textViewCurrentPace.text = stripUnit(viewModel.formatPace(session.currentPace))
        views.textViewCaloriesBurned.text = session.calories.toString()
    }

    fun updateButtonStates(state: WorkoutState) {
        when (state) {
            WorkoutState.NOT_STARTED -> {
                views.spinnerWorkoutType.visibility = View.VISIBLE
                views.buttonStart.visibility = View.VISIBLE
                views.buttonPause.visibility = View.GONE
                views.buttonStop.visibility = View.GONE
            }
            WorkoutState.RUNNING -> {
                views.spinnerWorkoutType.visibility = View.GONE
                views.buttonStart.visibility = View.GONE
                views.buttonPause.visibility = View.VISIBLE
                views.buttonStop.visibility = View.VISIBLE
                views.buttonPause.setImageResource(R.drawable.ic_pause)
            }
            WorkoutState.PAUSED -> {
                views.spinnerWorkoutType.visibility = View.GONE
                views.buttonStart.visibility = View.GONE
                views.buttonPause.visibility = View.VISIBLE
                views.buttonStop.visibility = View.VISIBLE
                views.buttonPause.setImageResource(R.drawable.ic_play_arrow)
            }
            WorkoutState.STOPPED -> {
                views.spinnerWorkoutType.visibility = View.VISIBLE
                views.buttonStart.visibility = View.VISIBLE
                views.buttonPause.visibility = View.GONE
                views.buttonStop.visibility = View.GONE
            }
        }
    }

    companion object {
        fun stripUnit(value: String): String {
            val trimmed = value.trim()
            val spaceIndex = trimmed.indexOf(' ')
            return if (spaceIndex > 0) trimmed.substring(0, spaceIndex) else trimmed
        }
    }
}
