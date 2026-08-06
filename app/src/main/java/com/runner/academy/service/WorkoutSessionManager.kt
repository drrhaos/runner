package com.runner.academy.service

import com.runner.academy.data.GpsStatus
import com.runner.academy.data.WorkoutSession
import com.runner.academy.util.FormatUtils
import com.runner.academy.util.SpeedPaceCalculator

/**
 * Manages workout session state, metrics aggregation, and timer logic.
 *
 * Responsibilities:
 *  - Start / pause / resume / stop transitions
 *  - Elapsed time tracking via periodic ticks
 *  - Distance, speed, pace, calories aggregation from processed GPS data
 *  - Track point collection within the session
 *  - Provide immutable snapshots of the current session state
 */
class WorkoutSessionManager {

    private var session: WorkoutSession = WorkoutSession()
    private var lastUpdateTime: Long = 0

    /** Callback invoked whenever the session state changes. */
    var onSessionChanged: ((WorkoutSession) -> Unit)? = null

    // ------------------------------------------------------------------
    // State queries
    // ------------------------------------------------------------------

    fun getSession(): WorkoutSession = session

    fun isTracking(): Boolean = session.isTracking

    // ------------------------------------------------------------------
    // Lifecycle transitions
    // ------------------------------------------------------------------

    fun startNewSession(
        initialGpsStatus: GpsStatus = GpsStatus.SEARCHING
    ) {
        val currentTime = System.currentTimeMillis()
        lastUpdateTime = 0L
        session = WorkoutSession(
            isTracking = true,
            isPaused = false,
            startTime = currentTime,
            pauseTime = 0L,
            totalPauseDuration = 0L,
            currentTime = 0L,
            distance = 0f,
            avgPace = 0f,
            currentPace = 0f,
            avgSpeed = 0f,
            currentSpeed = 0f,
            heartRate = 0,
            calories = 0,
            gpsStatus = initialGpsStatus,
            trackPoints = emptyList(),
            trackDataPoints = emptyList(),
            rawTrackDataPoints = emptyList(),
            currentLocation = null
        )
        notifyChanged()
    }

    /**
     * Restore an in-progress session after process death / sticky restart.
     * Does not zero metrics — unlike [startNewSession].
     */
    fun restoreSession(restored: WorkoutSession, metricsLastUpdateTime: Long = 0L) {
        lastUpdateTime = metricsLastUpdateTime
        session = restored
        notifyChanged()
    }

    fun getLastUpdateTime(): Long = lastUpdateTime

    fun pause() {
        val currentTime = System.currentTimeMillis()
        session = session.copy(
            isPaused = true,
            pauseTime = currentTime
        )
        notifyChanged()
    }

    fun resume() {
        val currentTime = System.currentTimeMillis()
        val pauseDuration = currentTime - session.pauseTime
        session = session.copy(
            isPaused = false,
            pauseTime = 0,
            totalPauseDuration = session.totalPauseDuration + pauseDuration
        )
        notifyChanged()
    }

    fun stop() {
        session = session.copy(
            isTracking = false,
            isPaused = false
        )
        notifyChanged()
    }

    // ------------------------------------------------------------------
    // Timer tick - called every second while actively tracking
    // ------------------------------------------------------------------

    /**
     * Advance elapsed workout time.
     * @param broadcast when false, only updates internal time (no listeners) —
     * used so the 1 Hz timer does not fan out voice/checkpoint/notification work.
     */
    fun tickElapsedTime(broadcast: Boolean = true) {
        val currentTime = System.currentTimeMillis()
        val elapsedTime = currentTime - session.startTime - session.totalPauseDuration
        session = session.copy(currentTime = elapsedTime)
        if (broadcast) notifyChanged()
    }

    // ------------------------------------------------------------------
    // GPS-driven metric updates
    // ------------------------------------------------------------------

    /**
     * Update session metrics based on a processed GPS location.
     *
     * @param segmentDistanceMeters Distance from the last accepted point (0 for first point).
     * @param trackPoints Updated display track points list.
     * @param trackDataPoints Updated data track points list.
     * @param rawTrackDataPoints Updated raw track points list.
     * @param userWeightKg User weight for calorie calculation.
     */
    fun updateMetricsFromLocation(
        segmentDistanceMeters: Float,
        trackPoints: List<org.osmdroid.util.GeoPoint>,
        trackDataPoints: List<com.runner.academy.data.TrackPoint>,
        rawTrackDataPoints: List<com.runner.academy.data.TrackPoint>,
        userWeightKg: Float,
        currentLocation: android.location.Location? = null
    ) {
        val newDistance = session.distance + segmentDistanceMeters / 1000f
        val currentTime = System.currentTimeMillis()
        val timeDiffMs = if (lastUpdateTime > 0) currentTime - lastUpdateTime else 0

        val segmentDistanceKm = segmentDistanceMeters / 1000.0
        val currentSpeed = SpeedPaceCalculator.computeCurrentSpeed(segmentDistanceKm, timeDiffMs)
        val avgSpeed = SpeedPaceCalculator.computeAverageSpeedKmH(newDistance.toDouble(), session.currentTime)
        val currentPace = SpeedPaceCalculator.computePaceRaw(currentSpeed)
        val avgPace = SpeedPaceCalculator.computePaceRaw(avgSpeed)
        val calories = FormatUtils.calculateCalories(newDistance, userWeightKg)

        session = session.copy(
            currentLocation = currentLocation ?: session.currentLocation,
            trackPoints = trackPoints,
            trackDataPoints = trackDataPoints,
            rawTrackDataPoints = rawTrackDataPoints,
            distance = newDistance,
            currentSpeed = currentSpeed,
            avgSpeed = avgSpeed,
            currentPace = currentPace,
            avgPace = avgPace,
            calories = calories,
            gpsStatus = GpsStatus.FOUND
        )
        lastUpdateTime = currentTime
        notifyChanged()
    }

    /**
     * Update session when a location is received but filtered out during active tracking.
     * Does NOT change gpsStatus (preserves existing status).
     */
    fun updateLocationOnly(
        currentLocation: android.location.Location,
        rawTrackDataPoints: List<com.runner.academy.data.TrackPoint>
    ) {
        session = session.copy(
            currentLocation = currentLocation,
            rawTrackDataPoints = rawTrackDataPoints
        )
        notifyChanged()
    }

    /**
     * Update session when tracking is paused/not-active but GPS is still found.
     * Sets gpsStatus to FOUND.
     */
    fun updateLocationWhenNotActive(
        currentLocation: android.location.Location,
        rawTrackDataPoints: List<com.runner.academy.data.TrackPoint>
    ) {
        session = session.copy(
            currentLocation = currentLocation,
            rawTrackDataPoints = rawTrackDataPoints,
            gpsStatus = GpsStatus.FOUND
        )
        notifyChanged()
    }

    /**
     * Update GPS status without changing other metrics.
     */
    fun updateGpsStatus(status: GpsStatus) {
        session = session.copy(gpsStatus = status)
        notifyChanged()
    }

    // ------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------

    private fun notifyChanged() {
        onSessionChanged?.invoke(session)
    }
}
