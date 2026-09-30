package com.runner.academy.util

import android.location.Location
import com.runner.academy.data.LocationSource
import com.runner.academy.data.TrackPoint
import com.runner.academy.data.WorkoutType
import com.runner.academy.data.maxReasonableGpsSpeedMps
import com.runner.academy.service.GpsLocationProcessor

/**
 * Save-time pipeline: turns the raw points of a finished session into the track that is
 * stored in the database (outliers dropped, near-duplicates merged, GPS gaps flagged).
 *
 * Shared by [com.runner.academy.ui.tracking.WorkoutTrackingViewModel] and the GPS replay tests.
 */
object TrackSanitizer {

    fun sanitize(rawPoints: List<TrackPoint>, workoutType: WorkoutType): List<TrackPoint> {
        if (rawPoints.isEmpty()) return emptyList()
        val result = mutableListOf<TrackPoint>()
        var previousLocation: Location? = null
        val maxReasonableSpeedMps = workoutType.maxReasonableGpsSpeedMps()

        for (point in rawPoints) {
            val rawLocation = toLocation(point)
            val forceGap = GpsFilter.isGapResume(previousLocation, rawLocation) || point.afterGap
            val filteredLocation = GpsFilter.filterGpsOutlier(
                rawLocation,
                previousLocation,
                maxReasonableSpeedMps = maxReasonableSpeedMps,
                forceGapResume = forceGap
            ) ?: continue

            val afterGap = forceGap && previousLocation != null
            if (!afterGap && previousLocation != null) {
                val segmentDistance = filteredLocation.distanceTo(previousLocation)
                if (segmentDistance < GpsLocationProcessor.MIN_POINT_DISTANCE_METERS) {
                    continue
                }
            }

            result.add(
                point.copy(
                    latitude = filteredLocation.latitude,
                    longitude = filteredLocation.longitude,
                    accuracy = filteredLocation.accuracy,
                    speed = filteredLocation.speed,
                    altitude = filteredLocation.altitude,
                    afterGap = afterGap,
                    source = point.source.ifBlank { LocationSource.GPS.name }
                )
            )
            previousLocation = filteredLocation
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
