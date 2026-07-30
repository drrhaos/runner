package com.runner.academy.service

import android.location.Location
import com.runner.academy.data.LocationSource
import com.runner.academy.data.TrackPoint
import com.runner.academy.data.WorkoutType
import com.runner.academy.data.maxReasonableGpsSpeedMps
import com.runner.academy.util.GpsFilter
import org.osmdroid.util.GeoPoint

/**
 * Processes raw GPS locations for workout tracking.
 *
 * Responsibilities:
 *  - Filter GPS outliers via [GpsFilter]
 *  - Resume track after GPS gaps without phantom distance
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
     * @param resumeAfterGap When true (e.g. session was [com.runner.academy.data.GpsStatus.LOST]),
     *   the first valid fix re-anchors the track with zero segment distance.
     */
    fun processLocation(
        location: Location,
        previousLocation: Location?,
        workoutType: WorkoutType,
        existingTrackPoints: MutableList<GeoPoint>,
        existingTrackDataPoints: MutableList<TrackPoint>,
        existingRawTrackDataPoints: MutableList<TrackPoint>,
        resumeAfterGap: Boolean = false
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
            altitude = location.altitude,
            afterGap = false,
            source = LocationSource.GPS.name
        )
        newRawTrackDataPoints.add(rawTrackPoint)

        val gapResume = GpsFilter.isGapResume(previousLocation, location, resumeAfterGap)

        // Filter GPS outlier (gap-aware)
        val filteredLocation = GpsFilter.filterGpsOutlier(
            location,
            previousLocation,
            workoutType.maxReasonableGpsSpeedMps(),
            forceGapResume = gapResume
        )
        if (filteredLocation == null) {
            android.util.Log.w(
                "GpsLocationProcessor",
                "GPS point filtered as outlier/invalid: lat=${location.latitude}, lon=${location.longitude}, acc=${location.accuracy}m"
            )
            decimateRawPointsIfNeeded(newRawTrackDataPoints)
            return ProcessResult.Rejected(
                trackPoints = newTrackPoints,
                trackDataPoints = newTrackDataPoints,
                rawTrackDataPoints = newRawTrackDataPoints,
                refreshGapClock = false
            )
        }

        // Minimum distance check — skipped on gap resume (new anchor).
        // Close points refresh the gap clock so slow jogging doesn't look like a GPS outage.
        if (!gapResume && previousLocation != null) {
            val distanceToLastMeters = filteredLocation.distanceTo(previousLocation)
            if (distanceToLastMeters < MIN_POINT_DISTANCE_METERS) {
                decimateRawPointsIfNeeded(newRawTrackDataPoints)
                return ProcessResult.Rejected(
                    trackPoints = newTrackPoints,
                    trackDataPoints = newTrackDataPoints,
                    rawTrackDataPoints = newRawTrackDataPoints,
                    refreshGapClock = true
                )
            }
        }

        val afterGap = gapResume && previousLocation != null

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
            altitude = filteredLocation.altitude,
            afterGap = afterGap,
            source = LocationSource.GPS.name
        )
        newTrackDataPoints.add(trackPoint)

        // Decimate if needed
        if (newTrackPoints.size == newTrackDataPoints.size) {
            decimateSyncedTrackPoints(newTrackPoints, newTrackDataPoints)
        }
        decimateRawPointsIfNeeded(newRawTrackDataPoints)

        // No phantom distance across a GPS gap
        val segmentDistanceMeters = if (previousLocation != null && !afterGap) {
            filteredLocation.distanceTo(previousLocation)
        } else {
            0f
        }

        return ProcessResult.Accepted(
            filteredLocation = filteredLocation,
            segmentDistanceMeters = segmentDistanceMeters,
            afterGap = afterGap,
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
            altitude = location.altitude,
            afterGap = false,
            source = LocationSource.GPS.name
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
            val afterGap: Boolean = false,
            override val trackPoints: MutableList<GeoPoint>,
            override val trackDataPoints: MutableList<TrackPoint>,
            override val rawTrackDataPoints: MutableList<TrackPoint>
        ) : ProcessResult

        data class Rejected(
            override val trackPoints: MutableList<GeoPoint>,
            override val trackDataPoints: MutableList<TrackPoint>,
            override val rawTrackDataPoints: MutableList<TrackPoint>,
            /** True when the fix was valid but too close — keep gap timer alive. */
            val refreshGapClock: Boolean = false
        ) : ProcessResult
    }
}
