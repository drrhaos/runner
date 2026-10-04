package com.runner.academy.util

import android.location.Location
import android.util.Log
import com.runner.academy.data.LocationSource
import com.runner.academy.data.TrackPoint
import com.runner.academy.data.WorkoutType
import com.runner.academy.data.maxReasonableGpsSpeedMps
import kotlin.math.max

/**
 * Per-run GPS fix filter: decides, fix by fix, what enters the track.
 *
 * One instance per workout, fed every fix in arrival order. Shared by the live path
 * ([com.runner.academy.service.GpsLocationProcessor], used by the tracking service) and the save path ([TrackSanitizer]),
 * so the track shown during the run and the stored one come from the same rules.
 *
 * State:
 *  - [anchor]: the last accepted fix; outlier checks and segment distance are measured from it;
 *  - gap clock: fix time of the last valid fix, accepted or near-duplicate, so standing still
 *    is not a GPS gap (see [GpsFilter.isGapResume]);
 *  - false-signal detector: only explicit violations are dropped (see [Reason]); doubtful
 *    fixes stay. A stretch of dropped fixes that ends with a good fix more than
 *    [GpsFilter.GAP_RESUME_THRESHOLD_MS] after the anchor is bridged by the straight line
 *    ([Verdict.Accepted.bridgeMeters]); a silence (no usable fixes at all for that long: tunnel,
 *    jamming, GNSS switched off by battery saver) is bridged only with steps, otherwise it
 *    stays a plain gap without distance;
 *  - steps (optional, [stepDistance] + `steps` per fix): a bridge is `max(straight line,
 *    steps × stride)` ([Verdict.Accepted.bridgeFromSteps] when steps win); see [pendingMeters]
 *    for an open episode or silence ([countSilence] while no fix arrives) and
 *    [Verdict.Accepted.leadInMeters] for steps before the first good fix.
 *
 * Distance bookkeeping: [countedMeters] = [committedMeters] (what the kept points add up to,
 * see [TrackGeometry.totalDistanceMeters]) + [pendingMeters] (steps over an open episode).
 * It never decreases: a closing bridge or lead-in is at least the pending distance it replaces,
 * so a live display that adds the change of [countedMeters] never counts a stretch twice and
 * never goes back.
 */
class TrackFilter(
    val workoutType: WorkoutType = WorkoutType.EASY_RUN,
    /** Steps → metres for bridges; null (no step sensor / no permission): straight lines only. */
    private val stepDistance: StepDistanceEstimator? = null
) {

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
            /**
             * Distance from the previous anchor when [afterGap] closes a dropped stretch: the
             * straight line, or steps × stride when longer ([bridgeFromSteps]).
             */
            val bridgeMeters: Float? = null,
            /** [bridgeMeters] came from steps (stored as `source = PEDOMETER`). */
            val bridgeFromSteps: Boolean = false,
            /**
             * On the first fix after a false start: distance run by steps before it (the track
             * itself begins here). Not part of [segmentDistanceMeters].
             */
            val leadInMeters: Float? = null
        ) : Verdict {
            /**
             * The track point for this fix, built from [from] (the raw point, or a stored point
             * when re-sanitizing): position from [location], steps / cadence / time from [from].
             * The one mapping shared by the live and the save path, so both store the same:
             *  - [bridgeMeters] on a bridge, the [leadInMeters] on the [firstPoint] of the track
             *    (see [TrackGeometry.leadInMeters]); a value already stored on [from] is kept;
             *  - `source = PEDOMETER` when the bridge came from steps.
             */
            fun toTrackPoint(from: TrackPoint, firstPoint: Boolean): TrackPoint = from.copy(
                latitude = location.latitude,
                longitude = location.longitude,
                accuracy = location.accuracy,
                speed = location.speed,
                altitude = location.altitude,
                afterGap = afterGap,
                bridgeMeters = when {
                    afterGap -> bridgeMeters ?: from.bridgeMeters
                    firstPoint -> leadInMeters ?: from.bridgeMeters
                    else -> null
                },
                source = if (bridgeFromSteps) LocationSource.PEDOMETER.name else from.source.ifBlank { LocationSource.GPS.name }
            )
        }

        /** Valid but closer than [GpsFilter.MIN_POINT_DISTANCE_METERS]: moves the gap clock only. */
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

    /** Sum of what the kept points add: segments, bridges and the lead-in. */
    var committedMeters: Float = 0f
        private set

    /**
     * Step distance of the open false-signal episode or silence, not yet in a kept point. Grows
     * (never shrinks) while it lasts and is replaced by the closing bridge or lead-in. Counted
     * only once the stretch is long enough to close as a bridge
     * ([GpsFilter.GAP_RESUME_THRESHOLD_MS] without a valid fix), or before the first good fix.
     */
    var pendingMeters: Float = 0f
        private set

    /** Distance counted so far: [committedMeters] + [pendingMeters]; never decreases. */
    val countedMeters: Float get() = committedMeters + pendingMeters

    private var lastValidFixTimeMs: Long? = null
    private var acceptedCount = 0

    // Steps
    private var anchorSteps: Int? = null
    /** Steps before the first good fix (false start, or no fix yet) become its lead-in. */
    private var leadInBeforeFirstFix = false
    private var startLeadInMeters = 0f

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
     * is then measured from the anchor's own fix time; [anchorSteps] are the steps at it and
     * [pendingMeters] the step distance of an episode already counted before the interruption.
     */
    fun reset(anchor: Location? = null, anchorSteps: Int? = null, pendingMeters: Float = 0f) {
        this.anchor = anchor
        this.anchorSteps = anchorSteps
        this.pendingMeters = pendingMeters.coerceAtLeast(0f)
        committedMeters = 0f
        leadInBeforeFirstFix = anchor == null && pendingMeters > 0f
        startLeadInMeters = 0f
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
     * @param steps Steps since the workout start at this fix; null without a step sensor.
     * @param cadence Steps/min around this fix, if known.
     */
    fun process(
        location: Location,
        forceGapResume: Boolean = false,
        steps: Int? = null,
        cadence: Float? = null
    ): Verdict {
        val verdict = decide(location, forceGapResume, steps, cadence)
        if (verdict is Verdict.Rejected && verdict.reason != Reason.INVALID) {
            updatePending(location.time, steps, cadence)
        }
        return verdict
    }

    private fun decide(location: Location, forceGapResume: Boolean, steps: Int?, cadence: Float?): Verdict {
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
                anchorSteps = null
                acceptedCount = 0
                lastValidFixTimeMs = null
                // A lead-in counted on the retracted start goes back to pending, not away
                committedMeters -= startLeadInMeters
                pendingMeters = max(pendingMeters, startLeadInMeters)
                startLeadInMeters = 0f
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
            filtered.distanceTo(previous) < GpsFilter.MIN_POINT_DISTANCE_METERS
        ) {
            markValid(location.time)
            return Verdict.NearDuplicate
        }

        val stepMeters = previous?.let { filtered.distanceTo(it) } ?: 0f
        // Fixes kept coming but were dropped: bridge the stretch. A short bridge (back where the
        // track stopped, e.g. a receiver that pins the position while standing) is an ordinary step.
        val closesDroppedStretch = gapResume && previous != null && droppedSinceValid > 0 && !silenceSinceValid
        val stepsMeters = if (gapResume && previous != null) {
            stepsMetersSince(previous, steps, location.time, cadence)
        } else {
            null
        }
        // Step distance already counted live (pendingMeters) stays counted
        val byStepsMeters = max(stepsMeters ?: 0f, pendingMeters)
        // A silence is bridged only by steps; without them it stays a plain gap
        val closesSilenceBySteps = gapResume && previous != null && !closesDroppedStretch && byStepsMeters > 0f
        val bridgeLength = if (closesDroppedStretch || closesSilenceBySteps) max(stepMeters, byStepsMeters) else 0f
        val bridge = (closesDroppedStretch || closesSilenceBySteps) &&
            (bridgeLength > MIN_BRIDGE_METERS || pendingMeters > 0f)
        val bridgeFromSteps = bridge && byStepsMeters > stepMeters
        val afterGap = gapResume && previous != null && (bridge || !closesDroppedStretch)
        val segment = when {
            previous == null -> 0f
            bridge -> bridgeLength
            !afterGap -> stepMeters
            else -> 0f // No phantom distance across a GPS gap
        }
        val leadIn = if (previous == null && leadInBeforeFirstFix) {
            max(stepsMetersSinceStart(steps, cadence) ?: 0f, pendingMeters).takeIf { it > 0f }
        } else {
            null
        }
        anchor = filtered
        anchorSteps = steps
        acceptedCount++
        committedMeters += segment + (leadIn ?: 0f)
        if (previous == null) startLeadInMeters = leadIn ?: 0f
        pendingMeters = 0f
        leadInBeforeFirstFix = false
        markValid(location.time)
        return Verdict.Accepted(
            filtered,
            segment,
            afterGap,
            bridgeMeters = if (bridge) bridgeLength else null,
            bridgeFromSteps = bridgeFromSteps,
            leadInMeters = leadIn
        )
    }

    /**
     * A clock tick while no fix arrives (the tracking service calls it every second): during a
     * silence — no usable fix for [GpsFilter.GAP_RESUME_THRESHOLD_MS] — or before the first good
     * fix, counts the step distance into [pendingMeters]. Without steps it does nothing.
     * [nowMs] is on the fixes' clock (wall time).
     */
    fun countSilence(nowMs: Long, steps: Int?, cadence: Float?) {
        if (stepDistance == null || steps == null) return
        val previous = anchor
        val meters = if (previous == null) {
            stepsMetersSinceStart(steps, cadence)?.also { leadInBeforeFirstFix = true }
        } else {
            val lastUsable = max(previous.time, lastUsableTimeMs ?: previous.time)
            if (nowMs - lastUsable < GpsFilter.GAP_RESUME_THRESHOLD_MS) return
            stepsMetersSince(previous, steps, nowMs, cadence)
        } ?: return
        pendingMeters = max(pendingMeters, meters)
    }

    /** Counts the open episode's step distance, see [pendingMeters]. */
    private fun updatePending(timeMs: Long, steps: Int?, cadence: Float?) {
        if (!inFalseSignal) return
        val previous = anchor
        val meters = if (previous == null) {
            if (!leadInBeforeFirstFix) return
            stepsMetersSinceStart(steps, cadence)
        } else {
            val lastValid = max(previous.time, lastValidFixTimeMs ?: previous.time)
            if (timeMs - lastValid < GpsFilter.GAP_RESUME_THRESHOLD_MS) return
            stepsMetersSince(previous, steps, timeMs, cadence)
        } ?: return
        pendingMeters = max(pendingMeters, meters)
    }

    /** Steps × stride from [from] (the anchor) to a fix at [timeMs], at the stretch's mean cadence. */
    private fun stepsMetersSince(from: Location, steps: Int?, timeMs: Long, cadence: Float?): Float? {
        val estimator = stepDistance ?: return null
        val delta = (steps ?: return null) - (anchorSteps ?: return null)
        if (delta < 0) return null
        val elapsedMs = timeMs - from.time
        val meanCadence = if (elapsedMs >= MIN_MEAN_CADENCE_SPAN_MS) delta * 60_000f / elapsedMs else cadence
        return estimator.distanceMeters(delta, meanCadence)
    }

    /** Steps × stride since the workout start (steps count from 0 there). */
    private fun stepsMetersSinceStart(steps: Int?, cadence: Float?): Float? {
        val estimator = stepDistance ?: return null
        val total = steps ?: return null
        if (total <= 0) return null
        return estimator.distanceMeters(total, cadence)
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
            if (anchor == null) leadInBeforeFirstFix = true
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

        /** Shorter stretches use the sensor's cadence instead of steps / time. */
        private const val MIN_MEAN_CADENCE_SPAN_MS = 5_000L
    }
}
