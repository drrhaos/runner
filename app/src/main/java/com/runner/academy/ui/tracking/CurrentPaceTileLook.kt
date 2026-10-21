package com.runner.academy.ui.tracking

import android.content.Context
import com.runner.academy.R
import com.runner.academy.data.LivePace
import com.runner.academy.util.FormatUtils
import com.runner.academy.util.SpeedPaceCalculator
import com.runner.academy.util.SpeedPaceUnits

/**
 * What the current-pace tile shows for the session's [LivePace] (null: none yet or no data).
 * The colour never changes: a pace by steps is fresh, only marked with «≈». TalkBack gets the
 * pace in words, since it reads «≈» as "almost equal to".
 *
 * @param text the value: "4:52", "≈ 4:52" (a no-break space) or the `--:--` placeholder.
 * @param contentDescription the tile's description.
 */
data class CurrentPaceTileLook(val text: String, val contentDescription: String) {

    companion object {
        /** [metric] false: the same pace per mile. */
        fun of(pace: LivePace?, context: Context, metric: Boolean = true): CurrentPaceTileLook {
            if (pace == null) {
                return CurrentPaceTileLook(
                    context.getString(R.string.track_info_time_placeholder),
                    context.getString(R.string.tracking_pace_tile_no_data_a11y)
                )
            }
            val perUnit = if (metric) pace.minPerKm else pace.minPerKm * SpeedPaceUnits.KM_PER_MILE
            val digits = SpeedPaceCalculator.formatPaceMmSs(perUnit)
            val spoken = FormatUtils.formatPaceForTTS(perUnit, context, metric)
            return if (pace.approximate) {
                CurrentPaceTileLook(
                    context.getString(R.string.tracking_pace_approximate, digits),
                    context.getString(R.string.tracking_pace_tile_approx_a11y, spoken)
                )
            } else {
                CurrentPaceTileLook(digits, context.getString(R.string.tracking_pace_tile_a11y, spoken))
            }
        }
    }
}
