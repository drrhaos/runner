package com.runner.academy.ui.records

import android.content.Context
import androidx.annotation.StringRes
import com.runner.academy.R
import com.runner.academy.data.BestEffort
import com.runner.academy.data.RecordDistance
import com.runner.academy.util.FormatUtils
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date

/** Texts shared by the records screen and the record card. */
object RecordsText {

    /** The name of a distance as shown, and as TalkBack reads it ("5 kilometers" rather than "5 km"). */
    private class Names(@StringRes val shown: Int, @StringRes val spoken: Int)

    private fun names(distance: RecordDistance): Names = when (distance) {
        RecordDistance.KM_1 -> Names(R.string.records_distance_1k, R.string.records_distance_1k_a11y)
        RecordDistance.KM_5 -> Names(R.string.records_distance_5k, R.string.records_distance_5k_a11y)
        RecordDistance.KM_10 -> Names(R.string.records_distance_10k, R.string.records_distance_10k_a11y)
        RecordDistance.HALF_MARATHON -> Names(R.string.records_distance_half, R.string.records_distance_half)
        RecordDistance.MARATHON -> Names(R.string.records_distance_marathon, R.string.records_distance_marathon)
    }

    fun name(context: Context, distance: RecordDistance): String = context.getString(names(distance).shown)

    fun spokenName(context: Context, distance: RecordDistance): String = context.getString(names(distance).spoken)

    /** The record time, "≈" before it when part of the window was counted from steps. */
    fun time(context: Context, effort: BestEffort): String {
        val time = FormatUtils.formatRecordTime(effort.elapsedMs)
        return if (effort.stepsShare > 0f) context.getString(R.string.records_approx_time, time) else time
    }

    /** "5 km", "21.1 km": the distance to run for a record not reached yet, no needless decimal. */
    fun distanceToRun(context: Context, distance: RecordDistance): String {
        val km = NumberFormat.getNumberInstance(context.resources.configuration.locales[0]).apply {
            minimumFractionDigits = 0
            maximumFractionDigits = 1
        }.format(distance.meters / 1000.0)
        return context.getString(R.string.records_distance_km, km)
    }

    /** Pace over the window, min/km, in the format of the details. */
    fun pace(context: Context, effort: BestEffort): String {
        val paceMinPerKm = (effort.elapsedMs / 60_000f) / (effort.distanceM / 1000f)
        return FormatUtils.formatPace(paceMinPerKm, context)
    }

    fun spokenDate(date: Date): String = DateFormat.getDateInstance(DateFormat.LONG).format(date)
}
