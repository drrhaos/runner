package com.runner.academy.ui.workout

import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.isVisible
import com.google.android.material.color.MaterialColors
import com.runner.academy.R
import com.runner.academy.data.Workout
import com.runner.academy.data.WorkoutType
import com.runner.academy.databinding.ItemLapRowBinding
import com.runner.academy.util.LapsJson
import com.runner.academy.util.MovingTimeDisplay
import java.text.NumberFormat
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
            displayIdentity(workout)
            displayLaps(workout)

            textViewDetailDistance.text = com.runner.academy.util.FormatUtils.formatDistance(workout.distance, context)
            // The time next to the pace is the moving one; the elapsed time gets its own tile
            // only when it differs (otherwise one "Time", as before)
            textViewDetailDuration.text = viewModel.formatDuration(workout.movingDuration)
            textViewDetailPace.text = viewModel.formatPace(workout.avgPace, context)
            val showsElapsed = MovingTimeDisplay.detailShowsElapsed(workout.duration, workout.movingDuration)
            textViewDetailDurationLabel.setText(
                if (showsElapsed) R.string.workout_moving_time_label else R.string.workout_time_label
            )
            layoutDetailElapsed.visibility = if (showsElapsed) View.VISIBLE else View.GONE
            updateThirdRow()
            textViewDetailElapsed.text = viewModel.formatDuration(workout.duration)

            val avgSpeed = com.runner.academy.util.FormatUtils.calculateAverageSpeed(workout.distance, workout.movingDuration)
            textViewDetailAvgSpeed.text = com.runner.academy.util.FormatUtils.formatSpeed(avgSpeed, true, context)

            val calories = workout.calories ?: 0
            textViewDetailCalories.text = com.runner.academy.util.FormatUtils.formatCalories(calories, context)

            // Each tile is one TalkBack group: "25:30" alone would be read as "twenty-five colon thirty"
            layoutDetailDistance.contentDescription = DetailTileText.distanceA11y(context, workout.distance)
            layoutDetailDuration.contentDescription =
                DetailTileText.durationA11y(context, workout.movingDuration, showsElapsed)
            layoutDetailPace.contentDescription = DetailTileText.paceA11y(context, workout.avgPace)
            layoutDetailElapsed.contentDescription = DetailTileText.elapsedA11y(context, workout.duration)
            layoutDetailAvgSpeed.contentDescription = DetailTileText.speedA11y(context, avgSpeed)
            layoutDetailCalories.contentDescription = DetailTileText.caloriesA11y(context, calories)
        }
    }

    /**
     * What a workout from a file tells about itself: its name above the date, the device
     * under the type, the note (the file's description among them) under the tiles — up to
     * four lines, a tap shows it whole. Each is gone when the workout has none.
     */
    private fun displayIdentity(workout: Workout) {
        binding.apply {
            val title = WorkoutTitle.clean(workout.title)
            textViewDetailTitle.isVisible = title != null
            textViewDetailTitle.text = title
            // Under a name the date is a second line; without one it heads the card as before
            textViewDetailDate.setTextAppearance(
                if (title != null) R.style.TextAppearance_Runner_Subhead else R.style.TextAppearance_Runner_Title3
            )
            textViewDetailDate.setTypeface(null, if (title != null) Typeface.NORMAL else Typeface.BOLD)
            textViewDetailDate.setTextColor(
                MaterialColors.getColor(
                    textViewDetailDate,
                    if (title != null) {
                        com.google.android.material.R.attr.colorOnSurfaceVariant
                    } else {
                        com.google.android.material.R.attr.colorOnSurface
                    }
                )
            )

            val device = WorkoutTitle.clean(workout.device)
            textViewDetailDevice.isVisible = device != null
            textViewDetailDevice.text = device
            textViewDetailDevice.contentDescription = device?.let { context.getString(R.string.workout_device_a11y, it) }

            val notes = WorkoutTitle.clean(workout.notes)
            textViewDetailNotes.isVisible = notes != null
            textViewDetailNotes.text = notes
            showNotesWhole(false)
            textViewDetailNotes.setOnClickListener {
                showNotesWhole(textViewDetailNotes.maxLines == NOTES_LINES)
            }
        }
    }

    /** The note whole, or its first [NOTES_LINES] lines; TalkBack names what a tap will do. */
    private fun showNotesWhole(whole: Boolean) {
        val notes = binding.textViewDetailNotes
        notes.maxLines = if (whole) Int.MAX_VALUE else NOTES_LINES
        ViewCompat.replaceAccessibilityAction(
            notes,
            AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK,
            context.getString(if (whole) R.string.workout_notes_collapse else R.string.workout_notes_expand),
            null
        )
    }

    /**
     * The "Laps" card: a header and a row per lap of the file; gone below two laps. TalkBack
     * reads a row as one phrase, the header not at all (the phrase names each value).
     */
    private fun displayLaps(workout: Workout) {
        val table = LapTable.of(LapsJson.parse(workout.lapsJson))
        binding.cardLaps.isVisible = table != null
        ViewCompat.setAccessibilityHeading(binding.textViewLapsTitle, true)
        val rows = binding.layoutLapRows
        rows.removeAllViews()
        if (table == null) return
        val inflater = LayoutInflater.from(context)

        val header = ItemLapRowBinding.inflate(inflater, rows, true)
        val muted = header.textViewLapNumber.currentTextColor
        lapCells(header).zip(
            listOf(
                R.string.workout_laps_col_number, R.string.workout_laps_col_km, R.string.workout_laps_col_time,
                R.string.workout_laps_col_pace, R.string.workout_laps_col_heart_rate
            )
        ).forEach { (view, label) ->
            view.setText(label)
            view.setTextAppearance(R.style.TextAppearance_Runner_Caption)
            view.setTextColor(muted)
        }
        header.textViewLapHeartRate.isVisible = table.showsHeartRate
        header.root.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS

        for (row in table.rows) {
            val line = ItemLapRowBinding.inflate(inflater, rows, true)
            lapCells(line).zip(listOf(row.number, row.km, row.time, row.pace, row.heartRate))
                .forEach { (view, text) -> view.text = text }
            line.textViewLapHeartRate.isVisible = table.showsHeartRate
            line.root.contentDescription = if (table.showsHeartRate) {
                context.getString(R.string.workout_lap_row_hr_a11y, row.number, row.km, row.time, row.pace, row.heartRate)
            } else {
                context.getString(R.string.workout_lap_row_a11y, row.number, row.km, row.time, row.pace)
            }
            line.root.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            ViewCompat.setScreenReaderFocusable(line.root, true)
            lapCells(line).forEach { it.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        }
    }

    private fun lapCells(line: ItemLapRowBinding) = listOf(
        line.textViewLapNumber, line.textViewLapKm, line.textViewLapTime, line.textViewLapPace, line.textViewLapHeartRate
    )

    /**
     * The cadence tile: the stored value, "—" while the background pass has not reached a
     * track with steps, hidden when there is no cadence (no sensor or permission, an older or
     * manual workout) — an empty tile would explain nothing.
     */
    fun displayCadence(display: CadenceDisplay) {
        binding.apply {
            layoutDetailCadence.visibility = if (display == CadenceDisplay.None) View.GONE else View.VISIBLE
            when (display) {
                is CadenceDisplay.Value -> {
                    textViewDetailCadence.text = display.spm.toString()
                    layoutDetailCadence.contentDescription = CadenceText.averageA11y(context, display.spm)
                }
                CadenceDisplay.Pending -> {
                    textViewDetailCadence.setText(R.string.metric_pending_placeholder)
                    layoutDetailCadence.contentDescription = context.getString(R.string.workout_details_cadence_pending_a11y)
                }
                CadenceDisplay.None -> Unit
            }
        }
    }

    /**
     * The heart rate tile (its own row): the stored average, "—" while the background pass has
     * not reached a track with heart rate, hidden when there is none (no sensor, an older or
     * manual workout).
     */
    fun displayHeartRate(display: HeartRateSummaryDisplay) {
        binding.apply {
            layoutDetailHeartRateRow.visibility = if (display == HeartRateSummaryDisplay.None) View.GONE else View.VISIBLE
            when (display) {
                is HeartRateSummaryDisplay.Value -> {
                    textViewDetailHeartRate.text = NumberFormat.getIntegerInstance().format(display.avgBpm)
                    layoutDetailHeartRate.contentDescription = HeartRateText.averageA11y(context, display.avgBpm)
                }
                HeartRateSummaryDisplay.Pending -> {
                    textViewDetailHeartRate.setText(R.string.metric_pending_placeholder)
                    layoutDetailHeartRate.contentDescription =
                        context.getString(R.string.workout_details_heart_rate_pending_a11y)
                }
                HeartRateSummaryDisplay.None -> Unit
            }
        }
    }

    /**
     * The gain and loss tiles: the stored values, "—" (without a source note) while the
     * background pass has not reached a track with altitudes, hidden without altitudes. Under
     * them the source note for GPS and file altitudes; a tap calls [onSourceNoteClick].
     */
    fun displayElevation(display: ElevationDisplay, onSourceNoteClick: (com.runner.academy.data.ElevationSource) -> Unit) {
        binding.apply {
            val visibility = if (display == ElevationDisplay.None) View.GONE else View.VISIBLE
            layoutDetailElevationGain.visibility = visibility
            layoutDetailElevationLoss.visibility = visibility
            buttonElevationSourceNote.visibility = View.GONE
            when (display) {
                is ElevationDisplay.Value -> {
                    textViewDetailElevationGain.text = context.getString(R.string.workout_elevation_gain_value, display.gainM)
                    textViewDetailElevationLoss.text = context.getString(R.string.workout_elevation_loss_value, display.lossM)
                    layoutDetailElevationGain.contentDescription = ElevationText.gainA11y(context, display.gainM)
                    layoutDetailElevationLoss.contentDescription = ElevationText.lossA11y(context, display.lossM)
                    ElevationText.sourceTexts(display.source)?.let { texts ->
                        buttonElevationSourceNote.setText(texts.note)
                        buttonElevationSourceNote.visibility = View.VISIBLE
                        buttonElevationSourceNote.setOnClickListener { onSourceNoteClick(display.source) }
                    }
                }
                ElevationDisplay.Pending -> {
                    textViewDetailElevationGain.setText(R.string.metric_pending_placeholder)
                    textViewDetailElevationLoss.setText(R.string.metric_pending_placeholder)
                    layoutDetailElevationGain.contentDescription =
                        context.getString(R.string.workout_elevation_gain_pending_a11y)
                    layoutDetailElevationLoss.contentDescription =
                        context.getString(R.string.workout_elevation_loss_pending_a11y)
                }
                ElevationDisplay.None -> Unit
            }
        }
        updateThirdRow()
    }

    /**
     * The third row shows when any of its tiles does. Its tiles keep the columns of the rows
     * above: the elapsed time alone sits under the third column, the elevation tiles start left.
     */
    private fun updateThirdRow() {
        binding.apply {
            val elevationShown = layoutDetailElevationGain.isVisible
            val elapsedShown = layoutDetailElapsed.isVisible
            layoutDetailThirdRow.visibility = if (elevationShown || elapsedShown) View.VISIBLE else View.GONE
            layoutDetailThirdRow.gravity = if (elevationShown) android.view.Gravity.START else android.view.Gravity.END
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

    private companion object {
        /** Lines of a note shown before a tap opens it whole. */
        const val NOTES_LINES = 4
    }
}
