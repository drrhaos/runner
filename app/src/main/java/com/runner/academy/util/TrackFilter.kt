package com.runner.academy.util

import android.location.Location
import com.runner.academy.data.WorkoutType
import com.runner.academy.data.maxReasonableGpsSpeedMps
import com.runner.academy.service.GpsLocationProcessor

/**
 * Per-run GPS fix filter: decides, fix by fix, what enters the track.
 *
 * One instance per workout, fed every fix in arrival order. Shared by the live path
 * ([GpsLocationProcessor], used by the tracking service) and the save path ([TrackSanitizer]),
 * so the track shown during the run and the stored one come from the same rules.
 *
 * State:
 *  - [anchor]: the last accepted fix; outlier checks and segment distance are measured from it;
 *  - gap clock: fix time of the last valid fix, accepted or near-duplicate, so standing still
 *    is not a GPS gap (see [GpsFilter.isGapResume]).
 */
class TrackFilter(var workoutType: WorkoutType = WorkoutType.EASY_RUN) {

    /** What [process] decided for one fix. */
    sealed interface Verdict {
        /** The fix becomes a track point and the new [anchor]. */
        data class Accepted(
            val location: Location,
            /** Distance added from the previous anchor; 0 for the first fix and after a gap. */
            val segmentDistanceMeters: Float,
            /** The fix resumes the track after a break (no solid line from the previous point). */
            val afterGap: Boolean
        ) : Verdict

        /** Valid but closer than [GpsLocationProcessor.MIN_POINT_DISTANCE_METERS]: moves the gap clock only. */
        data object NearDuplicate : Verdict

        /** Outlier or invalid fix: the state does not change. */
        data object Rejected : Verdict
    }

    /** Last accepted fix, null before the first one. */
    var anchor: Location? = null
        private set

    private var lastValidFixTimeMs: Long? = null

    /**
     * Starts a new run. [anchor] continues an interrupted one (checkpoint restore): the gap
     * is then measured from the anchor's own fix time.
     */
    fun reset(anchor: Location? = null) {
        this.anchor = anchor
        lastValidFixTimeMs = null
    }

    /**
     * @param forceGapResume The fix re-anchors the track even without a time gap (the session
     *   was [com.runner.academy.data.GpsStatus.LOST], or a stored point is flagged after a gap).
     */
    fun process(location: Location, forceGapResume: Boolean = false): Verdict {
        val previous = anchor
        val gapResume = GpsFilter.isGapResume(previous, location, forceGapResume, lastValidFixTimeMs)
        val filtered = GpsFilter.filterGpsOutlier(
            location,
            previous,
            workoutType.maxReasonableGpsSpeedMps(),
            forceGapResume = gapResume,
            lastValidFixTimeMs = lastValidFixTimeMs
        ) ?: return Verdict.Rejected

        // Close points refresh the gap clock so slow jogging doesn't look like a GPS outage
        if (!gapResume && previous != null &&
            filtered.distanceTo(previous) < GpsLocationProcessor.MIN_POINT_DISTANCE_METERS
        ) {
            lastValidFixTimeMs = location.time
            return Verdict.NearDuplicate
        }

        val afterGap = gapResume && previous != null
        // No phantom distance across a GPS gap
        val segment = if (previous != null && !afterGap) filtered.distanceTo(previous) else 0f
        anchor = filtered
        lastValidFixTimeMs = location.time
        return Verdict.Accepted(filtered, segment, afterGap)
    }
}
