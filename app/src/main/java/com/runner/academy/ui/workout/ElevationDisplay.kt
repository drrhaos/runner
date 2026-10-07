package com.runner.academy.ui.workout

import android.content.Context
import androidx.annotation.StringRes
import com.runner.academy.R
import com.runner.academy.data.ElevationSource
import com.runner.academy.util.WorkoutDerivation
import kotlin.math.roundToInt

/**
 * The texts of an approximate elevation source: the note under the tiles, the subtitle of the
 * chart card and the explanation behind the note.
 */
data class ElevationSourceTexts(
    @StringRes val note: Int,
    @StringRes val chartSubtitle: Int,
    @StringRes val dialogTitle: Int,
    @StringRes val dialogMessage: Int
)

/** Elevation for TalkBack: the arrows would be read as "up arrow", "m" letter by letter. */
object ElevationText {

    /** Per source; none for the barometer (exact enough) and for no source. */
    private val sourceTexts = mapOf(
        ElevationSource.GPS to ElevationSourceTexts(
            note = R.string.workout_elevation_source_gps,
            chartSubtitle = R.string.workout_elevation_source_gps_short,
            dialogTitle = R.string.workout_elevation_source_dialog_title,
            dialogMessage = R.string.workout_elevation_source_dialog_message
        ),
        ElevationSource.FILE to ElevationSourceTexts(
            note = R.string.workout_elevation_source_file,
            chartSubtitle = R.string.workout_elevation_source_file_short,
            dialogTitle = R.string.workout_elevation_source_file_dialog_title,
            dialogMessage = R.string.workout_elevation_source_file_dialog_message
        )
    )

    fun sourceTexts(source: ElevationSource): ElevationSourceTexts? = sourceTexts[source]

    /** "124 metres", declined. */
    fun meters(context: Context, meters: Int): String =
        context.resources.getQuantityString(R.plurals.meters, meters, meters)

    fun gainA11y(context: Context, meters: Int): String =
        context.getString(R.string.workout_elevation_gain_a11y, meters(context, meters))

    fun lossA11y(context: Context, meters: Int): String =
        context.getString(R.string.workout_elevation_loss_a11y, meters(context, meters))

    /** "Elevation gain 124 metres, elevation loss 118 metres". */
    fun summaryA11y(context: Context, gainM: Int, lossM: Int): String =
        context.getString(R.string.workout_elevation_summary_a11y, gainA11y(context, gainM), lossA11y(context, lossM))

    fun pendingA11y(context: Context): String = context.getString(
        R.string.workout_elevation_summary_a11y,
        context.getString(R.string.workout_elevation_gain_pending_a11y),
        context.getString(R.string.workout_elevation_loss_pending_a11y)
    )
}

/**
 * What the details show for elevation — the tiles and the chart title alike, from the stored
 * [com.runner.academy.data.Workout.elevationGain] / `elevationLoss` / `elevationSource`.
 */
sealed interface ElevationDisplay {
    /** The stored gain and loss, whole metres, and where the altitudes came from. */
    data class Value(val gainM: Int, val lossM: Int, val source: ElevationSource) : ElevationDisplay

    /** Not computed yet (the background pass is behind) but the track has altitudes: "—". */
    data object Pending : ElevationDisplay

    /** No elevation (a manual workout, a track without altitudes): tiles hidden. */
    data object None : ElevationDisplay

    companion object {
        fun of(
            gain: Float?,
            loss: Float?,
            source: ElevationSource?,
            metricsVersion: Int,
            trackHasAltitude: Boolean
        ): ElevationDisplay {
            val storedGain = gain?.takeIf { it.isFinite() }
            val storedLoss = loss?.takeIf { it.isFinite() }
            return when {
                storedGain != null && storedLoss != null && source != null && source != ElevationSource.NONE ->
                    Value(storedGain.roundToInt(), storedLoss.roundToInt(), source)
                WorkoutDerivation.isPending(metricsVersion, trackHasAltitude) -> Pending
                else -> None
            }
        }
    }
}
