package com.runner.academy.ui.records

import android.content.Context
import androidx.annotation.StringRes
import com.runner.academy.R
import com.runner.academy.data.BestEffort
import com.runner.academy.data.RecordDistance
import com.runner.academy.util.FormatUtils
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** Texts shared by the records screen and the record card. */
object RecordsText {

    @StringRes
    fun nameRes(distance: RecordDistance): Int = when (distance) {
        RecordDistance.KM_1 -> R.string.records_distance_1k
        RecordDistance.KM_5 -> R.string.records_distance_5k
        RecordDistance.KM_10 -> R.string.records_distance_10k
        RecordDistance.HALF_MARATHON -> R.string.records_distance_half
        RecordDistance.MARATHON -> R.string.records_distance_marathon
    }

    /** The distance as TalkBack reads it: "5 kilometers" rather than "5 km". */
    @StringRes
    fun spokenNameRes(distance: RecordDistance): Int = when (distance) {
        RecordDistance.KM_1 -> R.string.records_distance_1k_a11y
        RecordDistance.KM_5 -> R.string.records_distance_5k_a11y
        RecordDistance.KM_10 -> R.string.records_distance_10k_a11y
        RecordDistance.HALF_MARATHON -> R.string.records_distance_half
        RecordDistance.MARATHON -> R.string.records_distance_marathon
    }

    /** The record time, "≈" before it when part of the window was counted from steps. */
    fun time(context: Context, effort: BestEffort): String {
        val time = FormatUtils.formatRecordTime(effort.elapsedMs)
        return if (effort.stepsShare > 0f) context.getString(R.string.records_approx_time, time) else time
    }

    /** "21.1 km": the distance to run for a record not reached yet. */
    fun distanceToRun(context: Context, distance: RecordDistance): String =
        String.format(Locale.getDefault(), "%.1f %s", distance.meters / 1000f, context.getString(R.string.unit_km))

    /** Pace over the window, min/km, in the format of the details. */
    fun pace(context: Context, effort: BestEffort): String {
        val paceMinPerKm = (effort.elapsedMs / 60_000f) / (effort.distanceM / 1000f)
        return FormatUtils.formatPace(paceMinPerKm, context)
    }

    fun spokenDate(date: Date): String = DateFormat.getDateInstance(DateFormat.LONG).format(date)
}
