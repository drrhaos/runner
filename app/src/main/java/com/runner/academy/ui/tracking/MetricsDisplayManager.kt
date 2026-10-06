package com.runner.academy.ui.tracking

import android.content.res.ColorStateList
import android.view.View
import android.widget.ImageButton
import android.widget.Spinner
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.color.MaterialColors
import com.runner.academy.R
import com.runner.academy.data.WorkoutSession
import com.runner.academy.data.WorkoutState

/** What the timer shows besides its digits. */
enum class TimerState {
    RUNNING, AUTO_PAUSED, PAUSED;

    companion object {
        /** A manual pause wins: it closes an open auto-pause. */
        fun of(session: WorkoutSession): TimerState = when {
            session.isPaused -> PAUSED
            session.isTracking && session.autoPaused -> AUTO_PAUSED
            else -> RUNNING
        }
    }
}

/**
 * Manages real-time metrics display and button state transitions on the workout tracking screen.
 *
 * Responsibilities:
 * - Updating time, distance, speed, pace TextViews
 * - The timer's state under it (the caption, or the Auto-paused / Paused chip) and its alpha
 * - Button state transitions (start/pause/stop) based on WorkoutState
 * - Formatting and displaying workout statistics
 */
class MetricsDisplayManager(
    private val views: Views,
    private val viewModel: WorkoutTrackingViewModel,
    private val onIdleControlsVisible: ((Boolean) -> Unit)? = null
) {

    /** The caption under a timer: [label] and [chip] take turns inside [container]. */
    data class TimerCaption(
        val container: View,
        val label: TextView,
        val chip: TextView
    )

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
        val timerCaption: TimerCaption,
        val timerCaptionExpanded: TimerCaption,
        val textViewElapsedTimeExpanded: TextView,
    )

    private var shownTimerState: TimerState? = null

    fun updateMetrics(session: WorkoutSession) {
        // The big timer is the moving time; on an auto-pause it rolls back in the same frame
        // as the chip appears (both come in one session snapshot)
        val formattedTime = viewModel.formatTime(session.movingTime)
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

        val elapsed = views.textViewElapsedTimeExpanded
        if (session.everAutoPaused) {
            elapsed.text = elapsed.context.getString(
                R.string.track_info_elapsed_time_format,
                viewModel.formatTime(session.currentTime)
            )
            elapsed.visibility = View.VISIBLE
        } else {
            elapsed.visibility = View.GONE
        }
        renderTimerState(TimerState.of(session))
    }

    private fun renderTimerState(state: TimerState) {
        if (state == shownTimerState) return
        // The first render sets the state without animating
        val animate = shownTimerState != null
        shownTimerState = state
        val alpha = if (state == TimerState.RUNNING) 1f else PAUSED_TIMER_ALPHA
        for (timer in listOf(views.textViewWorkoutTime, views.textViewWorkoutTimeExpanded)) {
            timer.animate().cancel()
            if (animate) timer.animate().alpha(alpha).setDuration(TRANSITION_MS).start() else timer.alpha = alpha
        }
        renderCaption(views.timerCaption, state, animate)
        renderCaption(views.timerCaptionExpanded, state, animate)
    }

    private fun renderCaption(caption: TimerCaption, state: TimerState, animate: Boolean) {
        val chip = caption.chip
        val context = chip.context
        // The container is the live region: a gone chip could not announce the way back
        caption.container.contentDescription = context.getString(
            when (state) {
                TimerState.RUNNING -> R.string.track_info_state_running_a11y
                TimerState.AUTO_PAUSED -> R.string.track_info_state_auto_paused_a11y
                TimerState.PAUSED -> R.string.track_info_state_paused
            }
        )
        if (state == TimerState.RUNNING) {
            chip.animate().cancel()
            chip.visibility = View.GONE
            caption.label.visibility = View.VISIBLE
            return
        }

        val auto = state == TimerState.AUTO_PAUSED
        val background = MaterialColors.getColor(
            chip,
            if (auto) com.google.android.material.R.attr.colorTertiaryContainer
            else com.google.android.material.R.attr.colorSurfaceVariant
        )
        val foreground = MaterialColors.getColor(
            chip,
            if (auto) com.google.android.material.R.attr.colorOnTertiaryContainer
            else com.google.android.material.R.attr.colorOnSurface
        )
        chip.setText(if (auto) R.string.track_info_state_auto_paused else R.string.track_info_state_paused)
        chip.backgroundTintList = ColorStateList.valueOf(background)
        chip.setTextColor(foreground)
        // Not by colour alone: the auto-pause also has the icon (and its own word)
        val icon = if (auto) {
            ContextCompat.getDrawable(context, R.drawable.ic_pause)?.mutate()?.apply {
                val size = (CHIP_ICON_DP * context.resources.displayMetrics.density).toInt()
                setBounds(0, 0, size, size)
                setTint(foreground)
            }
        } else {
            null
        }
        chip.setCompoundDrawablesRelative(icon, null, null, null)

        caption.label.visibility = View.GONE
        if (chip.visibility != View.VISIBLE) {
            chip.visibility = View.VISIBLE
            chip.animate().cancel()
            if (animate) {
                chip.alpha = 0f
                chip.animate().alpha(1f).setDuration(TRANSITION_MS).start()
            } else {
                chip.alpha = 1f
            }
        }
    }

    fun updateButtonStates(state: WorkoutState) {
        when (state) {
            WorkoutState.NOT_STARTED -> {
                views.spinnerWorkoutType.visibility = View.VISIBLE
                views.buttonStart.visibility = View.VISIBLE
                views.buttonPause.visibility = View.GONE
                views.buttonStop.visibility = View.GONE
                onIdleControlsVisible?.invoke(true)
            }
            WorkoutState.RUNNING -> {
                views.spinnerWorkoutType.visibility = View.GONE
                views.buttonStart.visibility = View.GONE
                views.buttonPause.visibility = View.VISIBLE
                views.buttonStop.visibility = View.VISIBLE
                // An auto-pause is RUNNING too: the button pauses by hand
                views.buttonPause.setImageResource(R.drawable.ic_pause)
                views.buttonPause.contentDescription =
                    views.buttonPause.context.getString(R.string.btn_pause_description)
                onIdleControlsVisible?.invoke(false)
            }
            WorkoutState.PAUSED -> {
                views.spinnerWorkoutType.visibility = View.GONE
                views.buttonStart.visibility = View.GONE
                views.buttonPause.visibility = View.VISIBLE
                views.buttonStop.visibility = View.VISIBLE
                views.buttonPause.setImageResource(R.drawable.ic_play_arrow)
                views.buttonPause.contentDescription =
                    views.buttonPause.context.getString(R.string.btn_resume_description)
                onIdleControlsVisible?.invoke(false)
            }
            WorkoutState.STOPPED -> {
                views.spinnerWorkoutType.visibility = View.VISIBLE
                views.buttonStart.visibility = View.VISIBLE
                views.buttonPause.visibility = View.GONE
                views.buttonStop.visibility = View.GONE
                onIdleControlsVisible?.invoke(true)
            }
        }
    }

    companion object {
        /** The timer's digits during an auto-pause or a manual pause. */
        const val PAUSED_TIMER_ALPHA = 0.6f
        private const val TRANSITION_MS = 150L
        private const val CHIP_ICON_DP = 14f

        fun stripUnit(value: String): String {
            val trimmed = value.trim()
            val spaceIndex = trimmed.indexOf(' ')
            return if (spaceIndex > 0) trimmed.substring(0, spaceIndex) else trimmed
        }
    }
}
