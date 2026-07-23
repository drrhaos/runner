package com.drrhaos.runner.service

import android.location.Location
import com.drrhaos.runner.data.TrackPoint
import com.drrhaos.runner.data.WorkoutType
import com.drrhaos.runner.data.maxReasonableGpsSpeedMps
import com.drrhaos.runner.util.GpsFilter
import org.osmdroid.util.GeoPoint

/**
 * Processes raw GPS locations for workout tracking.
 *
 * Responsibilities:
 *  - Filter GPS outliers via [GpsFilter]
 *  - Enforce minimum distance between points
 *  - Build [TrackPoint] and [GeoPoint] instances from validated locations
 *  - Decimate track collections when they exceed memory limits
 */
class GpsLocationProcessor {

    companion object {
        const val MIN_POINT_DISTANCE_METERS = 2f
        const val MAX_TRACK_POINTS_DISPLAY = 8000
        const val MAX_RAW_TRACK_POINTS = 15000
    }

    /**
     * Process a single raw [Location] for an active workout.
     *
     * @param location The new GPS fix from FusedLocationProvider.
     * @param previousLocation The last accepted location (null for first point).
     * @param workoutType Determines the maximum reasonable speed for outlier filtering.
     * @param existingTrackPoints Current display track points collection.
     * @param existingTrackDataPoints Current data track points collection.
     * @param existingRawTrackDataPoints Current raw track points collection.
     *
     * @return A [ProcessResult] containing the updated collections and computed segment info,
     *   or null if the point was filtered out entirely.
     */
    fun processLocation(
        location: Location,
        previousLocation: Location?,
        workoutType: WorkoutType,
        existingTrackPoints: MutableList<GeoPoint>,
        existingTrackDataPoints: MutableList<TrackPoint>,
        existingRawTrackDataPoints: MutableList<TrackPoint>
    ): ProcessResult? {
        val newTrackPoints = existingTrackPoints.toMutableList()
        val newTrackDataPoints = existingTrackDataPoints.toMutableList()
        val newRawTrackDataPoints = existingRawTrackDataPoints.toMutableList()

        // Add raw point first (before filtering)
        val rawTrackPoint = TrackPoint(
            latitude = location.latitude,
            longitude = location.longitude,
            timestamp = if (location.time > 0) location.time else System.currentTimeMillis(),
            accuracy = location.accuracy,
            speed = location.speed,
            altitude = location.altitude
        )
        newRawTrackDataPoints.add(rawTrackPoint)

        // Filter GPS outlier
        val filteredLocation = GpsFilter.filterGpsOutlier(
            location,
            previousLocation,
            workoutType.maxReasonableGpsSpeedMps()
        )
        if (filteredLocation == null) {
            android.util.Log.w(
                "GpsLocationProcessor",
                "GPS point filtered as outlier/invalid: lat=${location.latitude}, lon=${location.longitude}, acc=${location.accuracy}m"
            )
            decimateRawPointsIfNeeded(newRawTrackDataPoints)
            return ProcessResult.Rejected(
                newTrackPoints,
                newTrackDataPoints,
                newRawTrackDataPoints
            )
        }

        // Minimum distance check
        if (previousLocation != null) {
            val distanceToLastMeters = filteredLocation.distanceTo(previousLocation)
            if (distanceToLastMeters < MIN_POINT_DISTANCE_METERS) {
                decimateRawPointsIfNeeded(newRawTrackDataPoints)
                return ProcessResult.Rejected(
                    newTrackPoints,
                    newTrackDataPoints,
                    newRawTrackDataPoints
                )
            }
        }

        // Create GeoPoint for map display
        val validGeoPoint = GpsFilter.createValidGeoPoint(filteredLocation)
        if (validGeoPoint != null) {
            newTrackPoints.add(validGeoPoint)
        }

        // Create TrackPoint for data persistence
        val trackPoint = TrackPoint(
            latitude = filteredLocation.latitude,
            longitude = filteredLocation.longitude,
            timestamp = filteredLocation.time,
            accuracy = filteredLocation.accuracy,
            speed = filteredLocation.speed,
            altitude = filteredLocation.altitude
        )
        newTrackDataPoints.add(trackPoint)

        // Decimate if needed
        if (newTrackPoints.size == newTrackDataPoints.size) {
            decimateSyncedTrackPoints(newTrackPoints, newTrackDataPoints)
        }
        decimateRawPointsIfNeeded(newRawTrackDataPoints)

        // Compute segment distance
        val segmentDistanceMeters = if (previousLocation != null) {
            filteredLocation.distanceTo(previousLocation)
        } else {
            0f
        }

        return ProcessResult.Accepted(
            filteredLocation = filteredLocation,
            segmentDistanceMeters = segmentDistanceMeters,
            trackPoints = newTrackPoints,
            trackDataPoints = newTrackDataPoints,
            rawTrackDataPoints = newRawTrackDataPoints
        )
    }

    /**
     * Process a location when NOT actively tracking (paused or not started).
     * Still collects raw points and updates current location.
     */
    fun processLocationWhenNotTracking(
        location: Location,
        existingRawTrackDataPoints: List<TrackPoint>,
        isCurrentlyTracking: Boolean
    ): Pair<List<TrackPoint>, Location?> {
        if (!isCurrentlyTracking) {
            return existingRawTrackDataPoints to location
        }
        val newRaw = existingRawTrackDataPoints.toMutableList()
        val rawTrackPoint = TrackPoint(
            latitude = location.latitude,
            longitude = location.longitude,
            timestamp = if (location.time > 0) location.time else System.currentTimeMillis(),
            accuracy = location.accuracy,
            speed = location.speed,
            altitude = location.altitude
        )
        newRaw.add(rawTrackPoint)
        decimateRawPointsIfNeeded(newRaw)
        return newRaw to location
    }

    // ------------------------------------------------------------------
    // Decimation helpers
    // ------------------------------------------------------------------

    fun decimateSyncedTrackPoints(
        trackPoints: MutableList<GeoPoint>,
        trackDataPoints: MutableList<TrackPoint>
    ) {
        while (trackPoints.size > MAX_TRACK_POINTS_DISPLAY) {
            val before = trackPoints.size
            val newTp = trackPoints.filterIndexed { i, _ -> i % 2 == 0 || i == trackPoints.lastIndex }.toMutableList()
            val newTd = trackDataPoints.filterIndexed { i, _ -> i % 2 == 0 || i == trackDataPoints.lastIndex }.toMutableList()
            trackPoints.clear()
            trackPoints.addAll(newTp)
            trackDataPoints.clear()
            trackDataPoints.addAll(newTd)
            if (trackPoints.size >= before) break
        }
    }

    fun decimateRawPointsIfNeeded(raw: MutableList<TrackPoint>) {
        while (raw.size > MAX_RAW_TRACK_POINTS) {
            val before = raw.size
            val newR = raw.filterIndexed { i, _ -> i % 2 == 0 || i == raw.lastIndex }.toMutableList()
            raw.clear()
            raw.addAll(newR)
            if (raw.size >= before) break
        }
    }

    // ------------------------------------------------------------------
    // Sealed result types
    // ------------------------------------------------------------------

    sealed interface ProcessResult {
        val trackPoints: MutableList<GeoPoint>
        val trackDataPoints: MutableList<TrackPoint>
        val rawTrackDataPoints: MutableList<TrackPoint>

        data class Accepted(
            val filteredLocation: Location,
            val segmentDistanceMeters: Float,
            override val trackPoints: MutableList<GeoPoint>,
            override val trackDataPoints: MutableList<TrackPoint>,
            override val rawTrackDataPoints: MutableList<TrackPoint>
        ) : ProcessResult

        data class Rejected(
            override val trackPoints: MutableList<GeoPoint>,
            override val trackDataPoints: MutableList<TrackPoint>,
            override val rawTrackDataPoints: MutableList<TrackPoint>
        ) : ProcessResult
    }
}
