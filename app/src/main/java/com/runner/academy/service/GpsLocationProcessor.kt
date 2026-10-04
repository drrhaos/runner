package com.runner.academy.service

import android.location.Location
import com.runner.academy.data.LocationSource
import com.runner.academy.data.TrackPoint
import com.runner.academy.data.WorkoutType
import com.runner.academy.util.GpsFilter
import com.runner.academy.util.StepDistanceEstimator
import com.runner.academy.util.TrackFilter
import org.osmdroid.util.GeoPoint

/**
 * Processes raw GPS locations for workout tracking.
 *
 * Responsibilities:
 *  - Decide per fix via a per-run [TrackFilter] (outliers, gap resume, near-duplicates,
 *    false signal, bridges by steps)
 *  - Report the distance to show as a delta of [TrackFilter.countedMeters]
 *  - Build [TrackPoint] and [GeoPoint] instances from accepted locations
 *  - Decimate track collections when they exceed memory limits
 *
 * Stateful: one workout at a time; call [reset] when a workout starts or is restored.
 */
class GpsLocationProcessor {

    companion object {
        const val MAX_TRACK_POINTS_DISPLAY = 8000
        const val MAX_RAW_TRACK_POINTS = 15000
    }

    private var filter = TrackFilter()

    /** Last accepted fix of the current run, null before the first one. */
    val anchor: Location? get() = filter.anchor

    private var reportedMeters = 0f

    /**
     * Starts a new run of [workoutType]; [anchor] continues an interrupted one (see
     * [TrackFilter.reset]). [stepDistance] (null without steps) bridges dropped stretches by
     * steps for this run.
     */
    fun reset(
        workoutType: WorkoutType = WorkoutType.EASY_RUN,
        anchor: Location? = null,
        anchorSteps: Int? = null,
        pendingMeters: Float = 0f,
        stepDistance: StepDistanceEstimator? = null
    ) {
        filter = TrackFilter(workoutType, stepDistance)
        filter.reset(anchor, anchorSteps, pendingMeters)
        // What was counted before an interruption is already in the session distance
        reportedMeters = filter.countedMeters
    }

    /**
     * A clock tick without a fix (the service calls it every second): counts the step distance of
     * a silence, see [TrackFilter.countSilence]. Returns the distance to add to the display.
     */
    fun countSilence(nowMs: Long, steps: Int?, cadence: Float?): Float {
        filter.countSilence(nowMs, steps, cadence)
        val delta = (filter.countedMeters - reportedMeters).coerceAtLeast(0f)
        reportedMeters = maxOf(reportedMeters, filter.countedMeters)
        return delta
    }

    private var nextFixAfterPause = false

    /**
     * The run resumes after a pause (see [TrackFilter.onResume]); the next raw point is marked
     * [TrackPoint.afterPause] so the save path sees the pause too.
     */
    fun onResume() {
        filter.onResume()
        nextFixAfterPause = true
    }

    /** Step distance of the open false-signal episode, already in the reported distance. */
    val pendingStepMeters: Float get() = filter.pendingMeters

    /**
     * True while fixes keep coming but are dropped as a false signal (spoofing / jamming), see
     * [TrackFilter.inFalseSignal].
     */
    val inFalseSignal: Boolean get() = filter.inFalseSignal

    /**
     * Process a single raw [Location] for an active workout.
     *
     * @param resumeAfterGap When true (e.g. session was [com.runner.academy.data.GpsStatus.LOST]),
     *   the first valid fix re-anchors the track with zero segment distance.
     */
    fun processLocation(
        location: Location,
        existingTrackPoints: MutableList<GeoPoint>,
        existingTrackDataPoints: MutableList<TrackPoint>,
        existingRawTrackDataPoints: MutableList<TrackPoint>,
        resumeAfterGap: Boolean = false,
        steps: Int? = null,
        cadence: Float? = null
    ): ProcessResult {
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
            source = LocationSource.GPS.name,
            steps = steps,
            cadence = cadence,
            afterPause = if (nextFixAfterPause) true else null
        )
        nextFixAfterPause = false
        newRawTrackDataPoints.add(rawTrackPoint)
        decimateRawPointsIfNeeded(newRawTrackDataPoints)

        val verdict = filter.process(location, forceGapResume = resumeAfterGap, steps = steps, cadence = cadence)
        val distanceDelta = (filter.countedMeters - reportedMeters).coerceAtLeast(0f)
        reportedMeters = maxOf(reportedMeters, filter.countedMeters)
        if (verdict !is TrackFilter.Verdict.Accepted) {
            val retractStart = verdict is TrackFilter.Verdict.Rejected && verdict.retractStart
            if (verdict is TrackFilter.Verdict.Rejected) {
                android.util.Log.w(
                    "GpsLocationProcessor",
                    "GPS point dropped (${verdict.reason}): lat=${location.latitude}, lon=${location.longitude}, acc=${location.accuracy}m"
                )
            }
            if (retractStart) {
                // The start was the false signal: the track begins at the first good fix
                newTrackPoints.clear()
                newTrackDataPoints.clear()
            }
            return ProcessResult.Rejected(
                trackPoints = newTrackPoints,
                trackDataPoints = newTrackDataPoints,
                rawTrackDataPoints = newRawTrackDataPoints,
                refreshGapClock = verdict is TrackFilter.Verdict.NearDuplicate,
                retractedStart = retractStart,
                reason = (verdict as? TrackFilter.Verdict.Rejected)?.reason,
                distanceDeltaMeters = distanceDelta
            )
        }

        val filteredLocation = verdict.location

        // Create GeoPoint for map display
        val validGeoPoint = GpsFilter.createValidGeoPoint(filteredLocation)
        if (validGeoPoint != null) {
            newTrackPoints.add(validGeoPoint)
        }

        // Create TrackPoint for data persistence (the mapping the save path uses too)
        val trackPoint = verdict.toTrackPoint(
            rawTrackPoint.copy(timestamp = filteredLocation.time),
            firstPoint = newTrackDataPoints.isEmpty()
        )
        newTrackDataPoints.add(trackPoint)

        // Decimate if needed
        if (newTrackPoints.size == newTrackDataPoints.size) {
            decimateSyncedTrackPoints(newTrackPoints, newTrackDataPoints)
        }

        return ProcessResult.Accepted(
            filteredLocation = filteredLocation,
            segmentDistanceMeters = verdict.segmentDistanceMeters,
            afterGap = verdict.afterGap,
            bridgeMeters = verdict.bridgeMeters,
            distanceDeltaMeters = distanceDelta,
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
        while (trackPoints.size > MAX_TRACK_POINTS_DISPLAY &&
            trackPoints.size == trackDataPoints.size
        ) {
            val before = trackPoints.size
            val last = trackDataPoints.lastIndex
            val keep = BooleanArray(trackDataPoints.size) { i ->
                i % 2 == 0 ||
                    i == last ||
                    trackDataPoints[i].afterGap ||
                    (i + 1 <= last && trackDataPoints[i + 1].afterGap)
            }
            val newTp = mutableListOf<GeoPoint>()
            val newTd = mutableListOf<TrackPoint>()
            for (i in trackDataPoints.indices) {
                if (keep[i]) {
                    newTp.add(trackPoints[i])
                    newTd.add(trackDataPoints[i])
                }
            }
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
            val last = raw.lastIndex
            val newR = raw.filterIndexed { i, point ->
                i % 2 == 0 ||
                    i == last ||
                    point.afterGap ||
                    (i + 1 <= last && raw[i + 1].afterGap)
            }.toMutableList()
            raw.clear()
            raw.addAll(newR)
            if (raw.size >= before) break
        }
    }

    // ------------------------------------------------------------------
    // Sealed result types
    // ------------------------------------------------------------------

    sealed interface ProcessResult {
        /**
         * Distance to add to the shown total for this fix: the segment (or the rest of a bridge
         * / lead-in beyond the step distance already shown), or step distance counted live
         * during a false-signal episode. Never negative, never counts a stretch twice.
         */
        val distanceDeltaMeters: Float
        val trackPoints: MutableList<GeoPoint>
        val trackDataPoints: MutableList<TrackPoint>
        val rawTrackDataPoints: MutableList<TrackPoint>

        data class Accepted(
            val filteredLocation: Location,
            /** Includes [bridgeMeters] when the fix closes a dropped stretch. */
            val segmentDistanceMeters: Float,
            val afterGap: Boolean = false,
            /** Straight line over a dropped (false-signal) stretch, see [TrackPoint.bridgeMeters]. */
            val bridgeMeters: Float? = null,
            override val distanceDeltaMeters: Float = segmentDistanceMeters,
            override val trackPoints: MutableList<GeoPoint>,
            override val trackDataPoints: MutableList<TrackPoint>,
            override val rawTrackDataPoints: MutableList<TrackPoint>
        ) : ProcessResult

        data class Rejected(
            override val trackPoints: MutableList<GeoPoint>,
            override val trackDataPoints: MutableList<TrackPoint>,
            override val rawTrackDataPoints: MutableList<TrackPoint>,
            /** True when the fix was valid but too close — keep gap timer alive. */
            val refreshGapClock: Boolean = false,
            /**
             * The start point was part of a false signal and was removed: [trackPoints] and
             * [trackDataPoints] are now empty and replace the session's (distance was 0).
             */
            val retractedStart: Boolean = false,
            /** Why the filter dropped the fix; null for a near-duplicate. */
            val reason: TrackFilter.Reason? = null,
            override val distanceDeltaMeters: Float = 0f
        ) : ProcessResult
    }
}
