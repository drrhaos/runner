package com.runner.academy.ui.workout

import android.content.Context
import androidx.annotation.StringRes
import com.runner.academy.R
import com.runner.academy.data.ElevationSource
import com.runner.academy.util.WorkoutDerivation
import kotlin.math.roundToInt

/** Elevation for TalkBack: the arrows would be read as "up arrow", "m" letter by letter. */
object ElevationText {

    /** "124 metres", declined. */
    fun meters(context: Context, meters: Int): String =
        context.resources.getQuantityString(R.plurals.meters, meters, meters)

    fun gainA11y(context: Context, meters: Int): String =
        context.getString(R.string.workout_elevation_gain_a11y, meters(context, meters))

    fun lossA11y(context: Context, meters: Int): String =
        context.getString(R.string.workout_elevation_loss_a11y, meters(context, meters))

    /** The note under the tiles; null: no note (the barometer is exact enough). */
    @StringRes
    fun sourceNote(source: ElevationSource): Int? = when (source) {
        ElevationSource.GPS -> R.string.workout_elevation_source_gps
        ElevationSource.FILE -> R.string.workout_elevation_source_file
        ElevationSource.BAROMETER, ElevationSource.NONE -> null
    }

    /** The subtitle of the chart card; null: none. */
    @StringRes
    fun sourceShort(source: ElevationSource): Int? = when (source) {
        ElevationSource.GPS -> R.string.workout_elevation_source_gps_short
        ElevationSource.FILE -> R.string.workout_elevation_source_file_short
        ElevationSource.BAROMETER, ElevationSource.NONE -> null
    }

    /** Title and message of the explanation behind the note. */
    fun sourceDialog(source: ElevationSource): Pair<Int, Int>? = when (source) {
        ElevationSource.GPS ->
            R.string.workout_elevation_source_dialog_title to R.string.workout_elevation_source_dialog_message
        ElevationSource.FILE ->
            R.string.workout_elevation_source_file_dialog_title to R.string.workout_elevation_source_file_dialog_message
        ElevationSource.BAROMETER, ElevationSource.NONE -> null
    }
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
                metricsVersion < WorkoutDerivation.CURRENT_METRICS_VERSION && trackHasAltitude -> Pending
                else -> None
            }
        }
    }
}
