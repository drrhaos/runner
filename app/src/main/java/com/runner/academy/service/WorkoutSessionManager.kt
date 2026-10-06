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
    /** The owner of the session time; [WorkoutSession.clock] mirrors its state. */
    private var clock = SessionClock()

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
        initialGpsStatus: GpsStatus = GpsStatus.SEARCHING,
        strideModelState: String? = null,
        now: Long = System.currentTimeMillis()
    ) {
        val currentTime = now
        lastUpdateTime = 0L
        clock = SessionClock().apply { start(now) }
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
            currentLocation = null,
            strideModelState = strideModelState,
            clock = clock.state
        )
        notifyChanged()
    }

    /**
     * Restore an in-progress session after process death / sticky restart.
     * Does not zero metrics — unlike [startNewSession]. The clock continues from [WorkoutSession.clock].
     */
    fun restoreSession(restored: WorkoutSession, metricsLastUpdateTime: Long = 0L) {
        lastUpdateTime = metricsLastUpdateTime
        clock = SessionClock(restored.clock)
        session = restored.withClock()
        notifyChanged()
    }

    fun getLastUpdateTime(): Long = lastUpdateTime

    fun pause(now: Long = System.currentTimeMillis()) {
        clock.pauseManual(now)
        // The paused time shows exactly what a Stop now would save
        session = session.copy(
            isPaused = true,
            pauseTime = now,
            currentTime = clock.elapsedMs(now),
            movingTime = clock.movingMs(now)
        ).withClock()
        notifyChanged()
    }

    fun resume(now: Long = System.currentTimeMillis()) {
        val pauseDuration = now - session.pauseTime
        clock.resumeManual(now)
        session = session.copy(
            isPaused = false,
            pauseTime = 0,
            totalPauseDuration = session.totalPauseDuration + pauseDuration
        ).withClock()
        notifyChanged()
    }

    /**
     * Auto-pause from the detector; [at] may be backdated (see [SessionClock.enterAutoPause]).
     * The times are refreshed at [now] in the same snapshot as the flag, so the screen rolls the
     * timer back in the frame the chip appears.
     * @return false if the clock refused it (see the rules of [SessionClock]).
     */
    fun enterAutoPause(at: Long, now: Long = System.currentTimeMillis()): Boolean {
        val wasAutoPaused = clock.autoPaused
        clock.enterAutoPause(at)
        session = session.withTimesAt(now).withClock()
        notifyChanged()
        return !wasAutoPaused && clock.autoPaused
    }

    /** @return The length of the auto-pause just closed, or null if the clock refused the exit. */
    fun exitAutoPause(at: Long, now: Long = System.currentTimeMillis()): Long? {
        val wasAutoPaused = clock.autoPaused
        clock.exitAutoPause(at)
        session = session.withTimesAt(now).withClock()
        notifyChanged()
        if (!wasAutoPaused || clock.autoPaused) return null
        val closed = clock.pauses().last()
        return closed.end - closed.start
    }

    /** Stops the clock: open pauses close and the time freezes at [now]. */
    fun stop(now: Long = System.currentTimeMillis()) {
        clock.stop(now)
        session = session.copy(
            isTracking = false,
            isPaused = false,
            currentTime = clock.elapsedMs(now),
            movingTime = clock.movingMs(now)
        ).withClock()
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
    fun tickElapsedTime(broadcast: Boolean = true, now: Long = System.currentTimeMillis()) {
        session = session.withTimesAt(now).withClock()
        if (broadcast) notifyChanged()
    }

    /** See [WorkoutSession.openStepMeters]; no listeners (the next update carries it). */
    fun setOpenStepMeters(meters: Float) {
        session = session.copy(openStepMeters = meters)
    }

    /**
     * Distance counted by steps while no fix arrives (a silence, see
     * [GpsLocationProcessor.countSilence]).
     */
    fun addStepDistance(addedMeters: Float, userWeightKg: Float) {
        if (addedMeters <= 0f) return
        val newDistance = session.distance + addedMeters / 1000f
        val avgSpeed = SpeedPaceCalculator.computeAverageSpeedKmH(newDistance.toDouble(), session.movingTime)
        session = session.copy(
            distance = newDistance,
            avgSpeed = avgSpeed,
            avgPace = SpeedPaceCalculator.computePaceRaw(avgSpeed),
            calories = FormatUtils.calculateCalories(newDistance, userWeightKg)
        )
        notifyChanged()
    }

    // ------------------------------------------------------------------
    // GPS-driven metric updates
    // ------------------------------------------------------------------

    /**
     * Update session metrics based on a processed GPS location.
     *
     * @param segmentDistanceMeters Distance to add for this fix (see
     *   [GpsLocationProcessor.ProcessResult.distanceDeltaMeters]).
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
        currentLocation: android.location.Location? = null,
        gpsStatus: GpsStatus = GpsStatus.FOUND
    ) {
        val newDistance = session.distance + segmentDistanceMeters / 1000f
        val currentTime = System.currentTimeMillis()
        val timeDiffMs = if (lastUpdateTime > 0) currentTime - lastUpdateTime else 0

        val segmentDistanceKm = segmentDistanceMeters / 1000.0
        val currentSpeed = SpeedPaceCalculator.computeCurrentSpeed(segmentDistanceKm, timeDiffMs)
        val avgSpeed = SpeedPaceCalculator.computeAverageSpeedKmH(newDistance.toDouble(), session.movingTime)
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
            gpsStatus = gpsStatus
        )
        lastUpdateTime = currentTime
        notifyChanged()
    }

    /**
     * Update session when a location is received but filtered out during active tracking.
     * Keeps gpsStatus unless [gpsStatus] is given. [trackPoints] / [trackDataPoints]
     * replace the track when the filter retracted it (a false-signal start).
     * [addedDistanceMeters] is step distance counted during a false-signal episode.
     */
    fun updateLocationOnly(
        currentLocation: android.location.Location,
        rawTrackDataPoints: List<com.runner.academy.data.TrackPoint>,
        trackPoints: List<org.osmdroid.util.GeoPoint>? = null,
        trackDataPoints: List<com.runner.academy.data.TrackPoint>? = null,
        addedDistanceMeters: Float = 0f,
        userWeightKg: Float = 0f,
        gpsStatus: GpsStatus? = null
    ) {
        var updated = session.copy(
            currentLocation = currentLocation,
            rawTrackDataPoints = rawTrackDataPoints,
            trackPoints = trackPoints ?: session.trackPoints,
            trackDataPoints = trackDataPoints ?: session.trackDataPoints,
            gpsStatus = gpsStatus ?: session.gpsStatus
        )
        if (addedDistanceMeters > 0f) {
            val newDistance = updated.distance + addedDistanceMeters / 1000f
            val avgSpeed = SpeedPaceCalculator.computeAverageSpeedKmH(newDistance.toDouble(), updated.movingTime)
            updated = updated.copy(
                distance = newDistance,
                avgSpeed = avgSpeed,
                avgPace = SpeedPaceCalculator.computePaceRaw(avgSpeed),
                calories = FormatUtils.calculateCalories(newDistance, userWeightKg)
            )
        }
        session = updated
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

    /** Elapsed and moving time at [now]. */
    private fun WorkoutSession.withTimesAt(now: Long): WorkoutSession {
        val owner = this@WorkoutSessionManager.clock
        return copy(currentTime = owner.elapsedMs(now), movingTime = owner.movingMs(now))
    }

    /** Mirrors the clock's state into the session (the clock is the owner of the time). */
    private fun WorkoutSession.withClock(): WorkoutSession {
        val owner = this@WorkoutSessionManager.clock
        return copy(clock = owner.state, autoPaused = owner.autoPaused, everAutoPaused = owner.everAutoPaused)
    }

    private fun notifyChanged() {
        onSessionChanged?.invoke(session)
    }
}
