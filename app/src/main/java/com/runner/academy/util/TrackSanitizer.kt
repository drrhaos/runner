package com.runner.academy.util

import android.location.Location
import com.runner.academy.data.LocationSource
import com.runner.academy.data.TrackPoint
import com.runner.academy.data.WorkoutType

/**
 * Save-time pipeline: turns the raw points of a finished session into the track that is
 * stored in the database (outliers dropped, near-duplicates merged, GPS gaps flagged), with the
 * same per-run [TrackFilter] the live path uses.
 *
 * Shared by [com.runner.academy.ui.tracking.WorkoutTrackingViewModel] and the GPS replay tests.
 */
object TrackSanitizer {

    fun sanitize(rawPoints: List<TrackPoint>, workoutType: WorkoutType): List<TrackPoint> {
        if (rawPoints.isEmpty()) return emptyList()
        val result = mutableListOf<TrackPoint>()
        val filter = TrackFilter(workoutType)

        for (point in rawPoints) {
            val verdict = filter.process(toLocation(point), forceGapResume = point.afterGap)
            if (verdict !is TrackFilter.Verdict.Accepted) continue
            val filteredLocation = verdict.location
            result.add(
                point.copy(
                    latitude = filteredLocation.latitude,
                    longitude = filteredLocation.longitude,
                    accuracy = filteredLocation.accuracy,
                    speed = filteredLocation.speed,
                    altitude = filteredLocation.altitude,
                    afterGap = verdict.afterGap,
                    source = point.source.ifBlank { LocationSource.GPS.name }
                )
            )
        }
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
