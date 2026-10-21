package com.runner.academy.ui.workout

import android.content.Context
import com.runner.academy.R
import com.runner.academy.util.FormatUtils
import kotlin.math.roundToInt

/** Metrics as TalkBack should read them: "5:30" would be "five colon thirty", "km/h" letter by letter. */
internal object MetricSpeech {

    /** "5 kilometers 20 meters" — to the 10 m the two decimals of the tile show. */
    fun distance(context: Context, distanceKm: Float): String {
        val meters = (distanceKm * 100f).roundToInt().coerceAtLeast(0) * 10
        val km = meters / 1000
        val rest = meters % 1000
        val parts = mutableListOf<String>()
        if (km > 0) parts += context.resources.getQuantityString(R.plurals.kilometers, km, km)
        if (rest > 0 || parts.isEmpty()) parts += context.resources.getQuantityString(R.plurals.meters, rest, rest)
        return parts.joinToString(" ")
    }

    /** "11.8 kilometers per hour", "12 kilometers per hour" — to the one decimal the tile shows. */
    fun speed(context: Context, speed: Float, metric: Boolean): String {
        val tenths = (speed * 10f).roundToInt().coerceAtLeast(0)
        return if (tenths % 10 == 0) {
            val whole = tenths / 10
            val plural = if (metric) R.plurals.kilometers_per_hour else R.plurals.miles_per_hour
            context.resources.getQuantityString(plural, whole, whole)
        } else {
            // A fraction takes one form whatever the number ("11,8 километра")
            val fraction = if (metric) R.string.kilometers_per_hour_fraction_a11y else R.string.miles_per_hour_fraction_a11y
            context.getString(fraction, tenths / 10f)
        }
    }

    /** "5 minutes 30 seconds per kilometer" (per mile without [metric]). */
    fun pace(context: Context, paceMin: Float, metric: Boolean): String =
        FormatUtils.formatPaceForTTS(paceMin, context, metric)
}

/**
 * The `contentDescription` of each detail tile: one focus group, read as a phrase — the label
 * and the value in words.
 */
object DetailTileText {

    fun distanceA11y(context: Context, distanceKm: Float): String =
        context.getString(R.string.workout_details_distance_a11y, MetricSpeech.distance(context, distanceKm))

    /** The time next to the pace: the moving time when [showsElapsed] gives the elapsed one its own tile. */
    fun durationA11y(context: Context, durationMs: Long, showsElapsed: Boolean): String = context.getString(
        if (showsElapsed) R.string.workout_details_moving_time_a11y else R.string.workout_details_time_a11y,
        FormatUtils.formatTimeForTTS(durationMs, context)
    )

    fun elapsedA11y(context: Context, durationMs: Long): String =
        context.getString(R.string.workout_details_elapsed_time_a11y, FormatUtils.formatTimeForTTS(durationMs, context))

    /** The tile's pace is per km; a workout without one shows "--:--". */
    fun paceA11y(context: Context, paceMinPerKm: Float): String =
        if (paceMinPerKm.isFinite() && paceMinPerKm > 0f) {
            context.getString(R.string.workout_details_pace_a11y, MetricSpeech.pace(context, paceMinPerKm, metric = true))
        } else {
            context.getString(R.string.workout_details_pace_unknown_a11y)
        }

    fun speedA11y(context: Context, speedKmh: Float): String =
        context.getString(R.string.workout_details_average_speed_a11y, MetricSpeech.speed(context, speedKmh, metric = true))

    fun caloriesA11y(context: Context, calories: Int): String = context.getString(
        R.string.workout_details_calories_a11y,
        context.resources.getQuantityString(R.plurals.kilocalories, calories, calories)
    )
}
