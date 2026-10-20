package com.runner.academy.ui.workout

import com.runner.academy.util.SpeedPaceCalculator
import java.util.Locale

/**
 * A metric value as a bare number, as the workout details and the workout list show them — the
 * unit is in the caption under it ("Pace, min/km"), so values line up and read alike everywhere.
 */
object MetricValue {

    /** "5.02", to the 10 m. */
    fun distance(distanceKm: Float, locale: Locale = Locale.getDefault()): String =
        String.format(locale, "%.2f", distanceKm)

    /** "5:06"; "--:--" without a pace. */
    fun pace(paceMinPerKm: Float): String =
        SpeedPaceCalculator.formatPaceMmSs(paceMinPerKm.takeIf { it.isFinite() } ?: 0f)

    /** "11.8". */
    fun speed(speedKmh: Float, locale: Locale = Locale.getDefault()): String =
        String.format(locale, "%.1f", speedKmh)

    /** Calories, cadence, heart rate, metres. */
    fun whole(value: Int): String = value.toString()
}
