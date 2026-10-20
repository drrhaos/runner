package com.runner.academy.ui.workout

import com.runner.academy.data.Lap
import com.runner.academy.util.FormatUtils
import com.runner.academy.util.PaceMath
import com.runner.academy.util.SpeedPaceCalculator
import java.util.Locale

/**
 * The "Laps" card of a workout from a file: a row per lap (number, km, time the device counted,
 * pace, average heart rate). The heart rate column goes when no lap has one.
 */
data class LapTable(val rows: List<Row>, val showsHeartRate: Boolean) {

    data class Row(val number: String, val km: String, val time: String, val pace: String, val heartRate: String)

    companion object {

        /** A missing value. */
        const val NONE = "—"

        /** The table of [laps]; null for fewer than two (one lap is the whole run). */
        fun of(laps: List<Lap>, locale: Locale = Locale.getDefault()): LapTable? {
            if (laps.size < 2) return null
            val rows = laps.mapIndexed { index, lap ->
                val km = lap.distanceM?.takeIf { it > 0f }?.div(1000f)
                val timerMs = lap.timerMs?.takeIf { it > 0L }
                Row(
                    number = (index + 1).toString(),
                    km = km?.let { String.format(locale, "%.2f", it) } ?: NONE,
                    time = timerMs?.let(FormatUtils::formatRecordTime) ?: NONE,
                    pace = if (km != null && timerMs != null) {
                        SpeedPaceCalculator.formatPaceMmSs(PaceMath.avgPace(km, timerMs))
                    } else {
                        NONE
                    },
                    heartRate = lap.avgHr?.toString() ?: NONE
                )
            }
            return LapTable(rows, showsHeartRate = laps.any { it.avgHr != null })
        }
    }
}

