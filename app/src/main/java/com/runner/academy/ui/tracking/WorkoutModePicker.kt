package com.runner.academy.ui.tracking

import android.content.Context
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.runner.academy.R
import com.runner.academy.data.TrainingIcon
import com.runner.academy.data.colorRes
import com.runner.academy.data.drawableRes
import com.runner.academy.databinding.FragmentWorkoutTrackingBinding
import com.runner.academy.databinding.ItemWorkoutModeRowBinding
import com.runner.academy.databinding.SheetWorkoutModeBinding
import com.runner.academy.util.FormatUtils

/**
 * The workout mode button (a pill: round icon, name, unfold mark) and the bottom sheet it opens:
 * groups of modes with square icons, the chosen one tinted and ticked. Which modes exist and which
 * one is preferred stays with [TrackingModeController].
 */
class WorkoutModePicker(
    private val context: Context,
    private val binding: FragmentWorkoutTrackingBinding
) {
    private var sheet: BottomSheetDialog? = null

    fun showSelected(mode: TrackingWorkoutMode) {
        val name = buttonName(mode)
        binding.textWorkoutModeName.text = name
        binding.imageWorkoutModeIcon.showIcon(WorkoutModeSheet.icon(mode))
        binding.buttonWorkoutMode.contentDescription = context.getString(R.string.tracking_mode_button_a11y, name)
    }

    /** [planName]: the active plan, for the subtitle of today's workout. */
    fun open(
        options: List<TrackingWorkoutMode>,
        selected: TrackingWorkoutMode,
        planName: String?,
        onPick: (TrackingWorkoutMode) -> Unit
    ) {
        dismiss()
        val dialog = BottomSheetDialog(context)
        val inflater = LayoutInflater.from(dialog.context)
        val sheetBinding = SheetWorkoutModeBinding.inflate(inflater)
        for (group in WorkoutModeSheet.groups(options)) {
            val title = inflater.inflate(R.layout.item_workout_mode_section, sheetBinding.layoutModeGroups, false) as TextView
            title.setText(group.title)
            ViewCompat.setAccessibilityHeading(title, true)
            sheetBinding.layoutModeGroups.addView(title)
            for (mode in group.modes) {
                val row = ItemWorkoutModeRowBinding.inflate(inflater, sheetBinding.layoutModeGroups, false)
                bindRow(row, mode, chosen = mode == selected, planName = planName)
                row.root.setOnClickListener {
                    dialog.dismiss()
                    if (mode != selected) onPick(mode)
                }
                sheetBinding.layoutModeGroups.addView(row.root)
            }
        }
        dialog.setContentView(sheetBinding.root)
        dialog.setOnDismissListener { if (sheet === dialog) sheet = null }
        sheet = dialog
        dialog.show()
    }

    fun dismiss() {
        sheet?.dismiss()
        sheet = null
    }

    private fun bindRow(row: ItemWorkoutModeRowBinding, mode: TrackingWorkoutMode, chosen: Boolean, planName: String?) {
        row.imageModeIcon.showIcon(WorkoutModeSheet.icon(mode))
        row.textModeName.text = rowName(mode)
        row.textModeSubtitle.text = subtitle(mode, planName)
        row.imageModeTick.visibility = if (chosen) View.VISIBLE else View.GONE
        row.root.isSelected = chosen
        if (chosen) row.root.setBackgroundColor(ContextCompat.getColor(context, R.color.ios_blue_fill))
        row.textModeName.paint.isFakeBoldText = chosen
    }

    private fun ImageView.showIcon(icon: TrainingIcon) {
        setImageResource(icon.drawableRes())
        backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, icon.colorRes()))
    }

    private fun buttonName(mode: TrackingWorkoutMode): String = when (mode) {
        is TrackingWorkoutMode.PlanToday -> context.getString(R.string.tracking_mode_plan, mode.displayTitle())
        else -> rowName(mode)
    }

    /** In the sheet the group title already says "today's plan". */
    private fun rowName(mode: TrackingWorkoutMode): String = when (mode) {
        TrackingWorkoutMode.EasyRun -> context.getString(R.string.workout_type_easy_run)
        else -> mode.displayTitle()
    }

    private fun subtitle(mode: TrackingWorkoutMode, planName: String?): String = when (mode) {
        TrackingWorkoutMode.EasyRun -> context.getString(R.string.tracking_mode_easy_subtitle)
        is TrackingWorkoutMode.PlanToday -> {
            val day = mode.scheduled.scheduled.dayIndex + 1
            if (planName != null) context.getString(R.string.tracking_mode_plan_subtitle, planName, day)
            else context.getString(R.string.plan_day_label, day)
        }
        is TrackingWorkoutMode.Template -> compositionText(TemplateComposition.of(mode.data.segments))
    }

    /** "6 × 400 m · ≈ 38 min"; a single goal that is the whole workout is said once. */
    private fun compositionText(c: TemplateComposition): String {
        if (c.repeats == 0) return context.getString(R.string.template_scheme_empty)
        val total = c.totalMs?.let { ms ->
            val minutes = context.getString(R.string.tracking_mode_minutes, ((ms + 30_000) / 60_000).toInt())
            if (c.estimated) context.getString(R.string.tracking_mode_about, minutes) else minutes
        }
        val goal = c.goal
        val head = when {
            goal == null -> context.resources.getQuantityString(R.plurals.chart_segments_count, c.repeats, c.repeats)
            c.repeats == 1 && goal is TemplateComposition.Goal.Duration && goal.ms == c.totalMs -> null
            else -> goalText(goal).let { if (c.repeats > 1) context.getString(R.string.tracking_mode_repeats, c.repeats, it) else it }
        }
        return listOfNotNull(head, total).joinToString(" · ")
    }

    private fun goalText(goal: TemplateComposition.Goal): String = when (goal) {
        is TemplateComposition.Goal.Duration -> FormatUtils.formatTime(goal.ms)
        is TemplateComposition.Goal.Distance -> FormatUtils.formatDistanceMeters(goal.meters, context)
    }
}
