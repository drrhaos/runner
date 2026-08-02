package com.runner.academy.ui.trainingplan

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.NumberPicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.runner.academy.R
import com.runner.academy.databinding.DialogSegmentValuePickerBinding

internal fun NumberPicker.configure(min: Int, max: Int, value: Int) {
    minValue = min
    maxValue = max
    this.value = value.coerceIn(min, max)
    wrapSelectorWheel = true
}

internal fun formatSegmentDuration(totalSeconds: Int): String {
    val safe = totalSeconds.coerceAtLeast(0)
    val hours = safe / 3600
    val minutes = (safe % 3600) / 60
    val seconds = safe % 60
    return String.format("%d:%02d:%02d", hours, minutes, seconds)
}

internal fun formatSegmentDistance(context: Context, meters: Int): String {
    val safe = meters.coerceAtLeast(0)
    val km = safe / 1000
    val m = safe % 1000
    return if (km > 0) {
        context.getString(R.string.segment_distance_format_km_m, km, m)
    } else {
        context.getString(R.string.segment_distance_format_m, m)
    }
}

internal fun formatSegmentPace(context: Context, paceTotalSeconds: Int): String {
    if (paceTotalSeconds <= 0) {
        return context.getString(R.string.segment_pace_none)
    }
    val minutes = paceTotalSeconds / 60
    val seconds = paceTotalSeconds % 60
    return context.getString(R.string.segment_pace_format, minutes, seconds)
}

internal fun showDurationValuePicker(
    context: Context,
    titleRes: Int,
    initialSeconds: Int,
    onPicked: (Int) -> Unit,
    onCancel: (() -> Unit)? = null
) {
    val dialogBinding = DialogSegmentValuePickerBinding.inflate(LayoutInflater.from(context))
    dialogBinding.column3.visibility = View.VISIBLE

    val total = initialSeconds.coerceIn(0, 24 * 3600 + 59 * 60 + 59)
    dialogBinding.label1.setText(R.string.add_workout_hint_time_hours)
    dialogBinding.label2.setText(R.string.add_workout_hint_time_minutes)
    dialogBinding.label3.setText(R.string.add_workout_hint_time_seconds)
    dialogBinding.picker1.configure(0, 24, total / 3600)
    dialogBinding.picker2.configure(0, 59, (total % 3600) / 60)
    dialogBinding.picker3.configure(0, 59, total % 60)

    MaterialAlertDialogBuilder(context)
        .setTitle(titleRes)
        .setView(dialogBinding.root)
        .setPositiveButton(R.string.save) { _, _ ->
            val value = dialogBinding.picker1.value * 3600 +
                dialogBinding.picker2.value * 60 +
                dialogBinding.picker3.value
            onPicked(value.coerceAtLeast(1))
        }
        .setNegativeButton(R.string.cancel) { _, _ -> onCancel?.invoke() }
        .setOnCancelListener { onCancel?.invoke() }
        .show()
}

internal fun showDistanceValuePicker(
    context: Context,
    titleRes: Int,
    initialMeters: Int,
    onPicked: (Int) -> Unit,
    onCancel: (() -> Unit)? = null
) {
    val dialogBinding = DialogSegmentValuePickerBinding.inflate(LayoutInflater.from(context))

    val total = initialMeters.coerceIn(0, 50 * 1000 + 999)
    dialogBinding.label1.setText(R.string.segment_picker_km)
    dialogBinding.label2.setText(R.string.segment_picker_m)
    dialogBinding.picker1.configure(0, 50, total / 1000)
    dialogBinding.picker2.configure(0, 999, total % 1000)

    MaterialAlertDialogBuilder(context)
        .setTitle(titleRes)
        .setView(dialogBinding.root)
        .setPositiveButton(R.string.save) { _, _ ->
            val value = dialogBinding.picker1.value * 1000 + dialogBinding.picker2.value
            onPicked(value.coerceAtLeast(1))
        }
        .setNegativeButton(R.string.cancel) { _, _ -> onCancel?.invoke() }
        .setOnCancelListener { onCancel?.invoke() }
        .show()
}

internal fun showPaceValuePicker(
    context: Context,
    titleRes: Int,
    initialPaceSeconds: Int,
    onPicked: (Int) -> Unit,
    onCancel: (() -> Unit)? = null
) {
    val dialogBinding = DialogSegmentValuePickerBinding.inflate(LayoutInflater.from(context))

    val total = initialPaceSeconds.coerceIn(0, 30 * 60 + 59)
    dialogBinding.label1.setText(R.string.segment_picker_pace_min)
    dialogBinding.label2.setText(R.string.add_workout_hint_time_seconds)
    dialogBinding.picker1.configure(0, 30, total / 60)
    dialogBinding.picker2.configure(0, 59, total % 60)

    MaterialAlertDialogBuilder(context)
        .setTitle(titleRes)
        .setView(dialogBinding.root)
        .setPositiveButton(R.string.save) { _, _ ->
            onPicked(dialogBinding.picker1.value * 60 + dialogBinding.picker2.value)
        }
        .setNegativeButton(R.string.cancel) { _, _ -> onCancel?.invoke() }
        .setOnCancelListener { onCancel?.invoke() }
        .show()
}

internal fun showCountPicker(
    context: Context,
    titleRes: Int,
    min: Int,
    max: Int,
    initial: Int,
    onPicked: (Int) -> Unit,
    onCancel: (() -> Unit)? = null
) {
    val dialogBinding = DialogSegmentValuePickerBinding.inflate(LayoutInflater.from(context))
    dialogBinding.column2.visibility = View.GONE
    dialogBinding.label1.setText(R.string.template_wizard_intervals_label)
    dialogBinding.picker1.configure(min, max, initial)

    MaterialAlertDialogBuilder(context)
        .setTitle(titleRes)
        .setView(dialogBinding.root)
        .setPositiveButton(R.string.save) { _, _ -> onPicked(dialogBinding.picker1.value) }
        .setNegativeButton(R.string.cancel) { _, _ -> onCancel?.invoke() }
        .setOnCancelListener { onCancel?.invoke() }
        .show()
}
