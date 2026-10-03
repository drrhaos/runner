package com.runner.academy.util

import android.location.Location
import android.util.Log
import com.runner.academy.data.WorkoutType
import com.runner.academy.data.maxReasonableGpsSpeedMps
import com.runner.academy.service.GpsLocationProcessor
import kotlin.math.max

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
 *    is not a GPS gap (see [GpsFilter.isGapResume]);
 *  - false-signal detector: only explicit violations are dropped (see [Reason]); doubtful
 *    fixes stay. A stretch of dropped fixes that ends with a good fix more than
 *    [GpsFilter.GAP_RESUME_THRESHOLD_MS] after the anchor is bridged by the straight line
 *    ([Verdict.Accepted.bridgeMeters]); a silence (no usable fixes at all for that long, a
 *    tunnel) stays a plain gap without distance.
 */
class TrackFilter(var workoutType: WorkoutType = WorkoutType.EASY_RUN) {

    /** Why a fix was dropped. */
    enum class Reason {
        /** No usable position (coordinates, accuracy) or out of order: no signal, not a false one. */
        INVALID,

        /** Too far / too fast from the anchor for an ordinary step. */
        OUTLIER,

        /** [FROZEN_MIN_FIXES] or more consecutive fixes with bit-identical coordinates. */
        FROZEN,

        /** Reported speed above the workout's max reasonable speed. */
        REPORTED_SPEED,

        /** Resume after a gap that the workout's max speed cannot explain (teleport). */
        IMPLAUSIBLE_JUMP
    }

    /** What [process] decided for one fix. */
    sealed interface Verdict {
        /** The fix becomes a track point and the new [anchor]. */
        data class Accepted(
            val location: Location,
            /**
             * Distance added from the previous anchor: the step, or [bridgeMeters] over a dropped
             * stretch; 0 for the first fix and after a plain gap.
             */
            val segmentDistanceMeters: Float,
            /** The fix resumes the track after a break (no solid line from the previous point). */
            val afterGap: Boolean,
            /** Straight line from the previous anchor when [afterGap] closes a dropped stretch. */
            val bridgeMeters: Float? = null
        ) : Verdict

        /** Valid but closer than [GpsLocationProcessor.MIN_POINT_DISTANCE_METERS]: moves the gap clock only. */
        data object NearDuplicate : Verdict

        /**
         * The fix is dropped. With [retractStart] the only track point so far (the start) was part
         * of the false signal and must be removed too: the track begins at the first good fix.
         */
        data class Rejected(val reason: Reason, val retractStart: Boolean = false) : Verdict
    }

    /** Last accepted fix, null before the first one (or after a retracted start). */
    var anchor: Location? = null
        private set

    /**
     * True while fixes keep coming but are dropped as a false signal (spoofing / jamming), from
     * the first explicit violation (or [FALSE_SIGNAL_MIN_OUTLIERS] outliers in a row) until a
     * fix is accepted again.
     */
    var inFalseSignal: Boolean = false
        private set

    private var lastValidFixTimeMs: Long? = null
    private var acceptedCount = 0

    // Since the gap clock last moved
    private var droppedSinceValid = 0
    private var silenceSinceValid = false
    private var consecutiveOutliers = 0

    // Every usable fix, dropped or not
    private var lastUsableTimeMs: Long? = null
    private var lastUsableLat = Double.NaN
    private var lastUsableLon = Double.NaN
    private var identicalRun = 0

    /**
     * Starts a new run. [anchor] continues an interrupted one (checkpoint restore): the gap
     * is then measured from the anchor's own fix time.
     */
    fun reset(anchor: Location? = null) {
        this.anchor = anchor
        acceptedCount = if (anchor != null) 1 else 0
        lastValidFixTimeMs = null
        inFalseSignal = false
        droppedSinceValid = 0
        silenceSinceValid = false
        consecutiveOutliers = 0
        lastUsableTimeMs = anchor?.time
        lastUsableLat = Double.NaN
        lastUsableLon = Double.NaN
        identicalRun = 0
    }

    /**
     * @param forceGapResume The fix re-anchors the track even without a time gap (the session
     *   was [com.runner.academy.data.GpsStatus.LOST], or a stored point is flagged after a gap).
     */
    fun process(location: Location, forceGapResume: Boolean = false): Verdict {
        val previous = anchor
        if (!GpsFilter.isUsableFix(location) || (previous != null && location.time < previous.time)) {
            return Verdict.Rejected(Reason.INVALID)
        }
        noteUsable(location)

        if (identicalRun >= FROZEN_MIN_FIXES) {
            // The start is retracted only while it is the sole track point and part of the frozen run
            val retract = acceptedCount == 1 && previous != null &&
                previous.latitude == location.latitude && previous.longitude == location.longitude
            if (retract) {
                anchor = null
                acceptedCount = 0
                lastValidFixTimeMs = null
            }
            return drop(Reason.FROZEN, retractStart = retract)
        }

        val maxSpeedMps = workoutType.maxReasonableGpsSpeedMps()
        if (location.hasSpeed() && location.speed > maxSpeedMps) return drop(Reason.REPORTED_SPEED)

        val gapResume = GpsFilter.isGapResume(previous, location, forceGapResume, lastValidFixTimeMs)
        if (gapResume && previous != null &&
            location.distanceTo(previous) > GpsFilter.maxPlausibleDistanceMeters(previous, location, maxSpeedMps)
        ) {
            return drop(Reason.IMPLAUSIBLE_JUMP)
        }

        val filtered = GpsFilter.filterGpsOutlier(
            location,
            previous,
            maxSpeedMps,
            forceGapResume = gapResume,
            lastValidFixTimeMs = lastValidFixTimeMs
        ) ?: return drop(Reason.OUTLIER)

        // Close points refresh the gap clock so slow jogging doesn't look like a GPS outage
        if (!gapResume && previous != null &&
            filtered.distanceTo(previous) < GpsLocationProcessor.MIN_POINT_DISTANCE_METERS
        ) {
            markValid(location.time)
            return Verdict.NearDuplicate
        }

        val stepMeters = previous?.let { filtered.distanceTo(it) } ?: 0f
        // Fixes kept coming but were dropped: bridge the stretch. A short bridge (back where the
        // track stopped, e.g. a receiver that pins the position while standing) is an ordinary step.
        val closesDroppedStretch = gapResume && previous != null && droppedSinceValid > 0 && !silenceSinceValid
        val bridge = closesDroppedStretch && stepMeters > MIN_BRIDGE_METERS
        val afterGap = gapResume && previous != null && (bridge || !closesDroppedStretch)
        val segment = when {
            previous == null -> 0f
            !afterGap || bridge -> stepMeters // No phantom distance across a GPS gap
            else -> 0f
        }
        anchor = filtered
        acceptedCount++
        markValid(location.time)
        return Verdict.Accepted(filtered, segment, afterGap, bridgeMeters = if (bridge) stepMeters else null)
    }

    private fun noteUsable(location: Location) {
        val lastTime = lastUsableTimeMs
        if (lastTime != null && location.time - lastTime >= GpsFilter.GAP_RESUME_THRESHOLD_MS) {
            silenceSinceValid = true
        }
        lastUsableTimeMs = max(location.time, lastTime ?: location.time)
        val identical = location.latitude == lastUsableLat && location.longitude == lastUsableLon
        identicalRun = if (identical) identicalRun + 1 else 1
        lastUsableLat = location.latitude
        lastUsableLon = location.longitude
    }

    private fun drop(reason: Reason, retractStart: Boolean = false): Verdict.Rejected {
        droppedSinceValid++
        consecutiveOutliers = if (reason == Reason.OUTLIER) consecutiveOutliers + 1 else 0
        if (reason != Reason.OUTLIER || consecutiveOutliers >= FALSE_SIGNAL_MIN_OUTLIERS) {
            if (!inFalseSignal) Log.w(TAG, "False GPS signal: $reason")
            inFalseSignal = true
        }
        return Verdict.Rejected(reason, retractStart)
    }

    private fun markValid(fixTimeMs: Long) {
        lastValidFixTimeMs = fixTimeMs
        droppedSinceValid = 0
        silenceSinceValid = false
        consecutiveOutliers = 0
        inFalseSignal = false
    }

    companion object {
        private const val TAG = "TrackFilter"

        /** Real receivers jitter: this many identical fixes in a row are a replayed / frozen position. */
        const val FROZEN_MIN_FIXES = 3

        /** Ordinary outliers in a row that count as a false-signal episode (single ones are multipath). */
        const val FALSE_SIGNAL_MIN_OUTLIERS = 3

        /** A dropped stretch that ends this close to where the track stopped is not drawn as a bridge. */
        const val MIN_BRIDGE_METERS = 25f
    }
}
