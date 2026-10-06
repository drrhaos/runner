package com.runner.academy.util

import com.runner.academy.data.BestEffort
import com.runner.academy.data.ElevationSource
import com.runner.academy.data.TrackData
import com.runner.academy.data.Workout
import com.runner.academy.data.WorkoutType

/** What the derived columns of a workout are computed from. */
data class DerivationInput(
    val trackJson: String?,
    val type: WorkoutType,
    val durationMs: Long
)

/** The fastest window of a workout over one record distance (see [BestEffort]). */
data class Effort(
    val distanceM: Int,
    val elapsedMs: Long,
    val startTime: Long,
    val endTime: Long,
    val stepsShare: Float
) {
    fun toBestEffort(workoutId: Long) = BestEffort(workoutId, distanceM, elapsedMs, startTime, endTime, stepsShare)
}

/**
 * The derived columns of a workout and its best efforts. Null = no data (no or broken track,
 * no altitudes, no steps); [metricsVersion] tells that apart from "not computed yet" (0).
 */
data class Derived(
    val elevationGain: Float? = null,
    val elevationLoss: Float? = null,
    val elevationSource: ElevationSource? = null,
    val avgCadence: Float? = null,
    val routePreview: String? = null,
    val efforts: List<Effort> = emptyList(),
    val metricsVersion: Int = WorkoutDerivation.CURRENT_METRICS_VERSION
) {
    /** [workout] with every derived column overwritten: a stale value never survives. */
    fun applyTo(workout: Workout): Workout = workout.copy(
        elevationGain = elevationGain,
        elevationLoss = elevationLoss,
        elevationSource = elevationSource,
        avgCadence = avgCadence,
        routePreview = routePreview,
        metricsVersion = metricsVersion
    )
}

/**
 * The one computation of derived workout metrics, shared by every write path (live recording,
 * form, GPX import) and the background pass over older rows. Pure: one parse of the track,
 * no database.
 */
object WorkoutDerivation {

    /**
     * Version of the algorithms below. Raising it makes the background pass recompute every
     * row (a changed algorithm or a new record distance needs no migration).
     */
    const val CURRENT_METRICS_VERSION = 4 // 2: route preview, 3: cadence, 4: elevation (GPS/file)

    fun derive(input: DerivationInput): Derived {
        // The track the details screen shows, so derived metrics agree with the map and splits
        val track = DisplayTrack.of(input.trackJson, input.type)
            ?: return Derived() // no or broken track: computed, nothing derived, never retried
        return fromTrack(track, input)
    }

    /**
     * Every metric is computed from the same [track] here; each release-3 branch fills its
     * fields: routePreview (route preview), avgCadence (cadence), elevation* (elevation),
     * efforts (records). Raise [CURRENT_METRICS_VERSION] with each.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun fromTrack(track: TrackData, input: DerivationInput): Derived {
        val source = ElevationSource.of(track)
        val elevation = if (source == ElevationSource.NONE) null else ElevationGain.compute(track.points, source)
        return Derived(
            elevationGain = elevation?.gainM,
            elevationLoss = elevation?.lossM,
            elevationSource = if (elevation == null) ElevationSource.NONE else source,
            avgCadence = TrackCadence.average(track.points, track.pauses),
            routePreview = RoutePreviews.of(track.points)?.let(RoutePreviewCodec::encode)
        )
    }
}
