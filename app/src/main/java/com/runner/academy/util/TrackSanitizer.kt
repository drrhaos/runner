package com.runner.academy.util

import android.location.Location
import com.runner.academy.data.TrackPoint
import com.runner.academy.data.WorkoutType

/**
 * Save-time pipeline: turns the raw points of a finished session into the track that is
 * stored in the database (outliers dropped, near-duplicates merged, GPS gaps flagged), with the
 * same per-run [TrackFilter] the live path uses.
 *
 * Steps and cadence come from the raw points; with the run's [StepDistanceEstimator] (the
 * same frozen one the live path used) dropped stretches are bridged by steps exactly as live.
 *
 * Shared by [com.runner.academy.ui.tracking.WorkoutTrackingViewModel] and the GPS replay tests.
 */
object TrackSanitizer {

    /**
     * @param tailMeters Step distance counted live after the last fix that no fix closed
     *   ([com.runner.academy.data.WorkoutSession.openStepMeters]); stored as the track's tail
     *   ([TrackPoint.tailMeters]). A tail already on [rawPoints] (a stored track) is kept.
     */
    fun sanitize(
        rawPoints: List<TrackPoint>,
        workoutType: WorkoutType,
        stepDistance: StepDistanceEstimator? = null,
        tailMeters: Float = 0f
    ): List<TrackPoint> {
        if (rawPoints.isEmpty()) return emptyList()
        val result = mutableListOf<TrackPoint>()
        val filter = TrackFilter(workoutType, stepDistance)

        for (point in rawPoints) {
            val verdict = filter.process(
                toLocation(point),
                forceGapResume = point.afterGap,
                steps = point.steps,
                cadence = point.cadence
            )
            if (verdict is TrackFilter.Verdict.Rejected && verdict.retractStart) result.clear()
            if (verdict !is TrackFilter.Verdict.Accepted) continue
            // The tail belongs to the last point only
            result.add(verdict.toTrackPoint(point, firstPoint = result.isEmpty()).copy(tailMeters = null))
        }
        val tail = maxOf(tailMeters, rawPoints.last().tailMeters ?: 0f, filter.pendingMeters)
        if (tail > 0f && result.isNotEmpty()) result[result.lastIndex] = result.last().copy(tailMeters = tail)
        return result
    }

    fun toLocation(point: TrackPoint): Location {
        return Location("track").apply {
            latitude = point.latitude
            longitude = point.longitude
            time = if (point.timestamp > 0) point.timestamp else System.currentTimeMillis()
            accuracy = point.accuracy ?: 50f
            speed = point.speed ?: 0f
            point.altitude?.let { altitude = it }
        }
    }
}
