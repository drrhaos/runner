package com.runner.academy.voice

import com.runner.academy.data.IntervalLive
import com.runner.academy.data.SegmentTarget
import com.runner.academy.data.target
import com.runner.academy.sensor.HeartRateDisplay
import com.runner.academy.service.LivePaceTracker
import com.runner.academy.util.HrZones
import com.runner.academy.util.IntervalEngine

/** What one tick of the running workout gives the coach. */
data class CoachInput(
    /** The workout clock without manual pauses: the segments run on it. */
    val elapsedMs: Long,
    /** [elapsedMs] minus auto-pauses: the summaries run on it. */
    val movingMs: Long,
    val distanceM: Float,
    val autoPaused: Boolean,
    /** Minutes per km, as the session shows it; 0 before any distance. */
    val avgPace: Float,
    /** Read live each tick: a change mid-run applies at once. */
    val settings: VoiceSettings,
    val heartRate: HeartRateDisplay?,
    /** The service's segments; null: a free run. */
    val engine: IntervalEngine?,
    /** The run's live pace, already ticked this second; the target check cuts it at the segment's start. */
    val livePace: LivePaceTracker? = null,
    /** Null: the zones are off. */
    val zones: HrZones? = null,
    /** Speech is playing or queued: a target hint is not said over it. */
    val speechBusy: Boolean = false
)

data class CoachResult(
    /** In the order to say them. */
    val lines: List<VoiceLine>,
    /** The current segment and its target's verdict after this tick; null: a free run. */
    val interval: IntervalLive?
)

/**
 * Decides what the voice says on each 1 s tick of a running workout (not on a manual pause:
 * the timer stops). Pure: the service and the bench feed it and send the lines to a
 * [VoiceOutput]. One per workout, not checkpointed: after a restore [resume] keeps the summary
 * from speaking for the past, and the checkpointed cursor of the [IntervalEngine] keeps the beep
 * and the warning from repeating; the target hints' cooldown starts over.
 *
 * - The segments advance and their beep and warning are consumed on every tick, voice on or off:
 *   the cursor goes to the checkpoint and switching the voice on does not replay them.
 * - Order within a tick: [VoiceLine.SegmentStart] → [VoiceLine.SegmentUpcoming] →
 *   [VoiceLine.Summary] → [VoiceLine.TargetHint]. Status lines are the service's own: they are
 *   events and speak at once. They still keep the order beep → status → warning → summary: the
 *   beep is outside the speech queue, and the service queues the auto-pause line earlier in the
 *   same tick and the GPS and sensor lines between ticks, all before this tick's lines.
 * - A target hint never waits: it drops when anything else is said this tick or speech plays,
 *   without spending its cooldown, and comes again on the next tick ([TargetCheck]). The verdict
 *   is checked with the voice off too: the indicator shows it, and a hint due goes to the screen
 *   ([IntervalLive.hintDue]) when the voice or its target cues are off.
 * - The profile is the interval one when the workout has segments.
 */
class VoiceCoach {

    private val summary = SummaryClock()
    private val targetCheck = TargetCheck()

    /** Continues a restored workout from this position. */
    fun resume(movingMs: Long, distanceM: Float) {
        summary.resume(movingMs, distanceM)
    }

    fun onTick(input: CoachInput): CoachResult {
        val engine = input.engine
        val profile = input.settings.profileFor(hasSegments = engine != null)
        val lines = mutableListOf<VoiceLine>()

        val state = engine?.update(input.elapsedMs, input.distanceM)
        var remainingMs: Long? = null
        if (engine != null && state != null) {
            if (engine.consumeSegmentAnnouncement(state)) lines += VoiceLine.SegmentStart
            remainingMs = engine.estimateRemainingMs(state, input.avgPace)
            val next = engine.peekNextSegment()
            if (engine.consumeUpcomingWarning(state, remainingMs) && next != null) {
                lines += VoiceLine.SegmentUpcoming(next)
            }
            if (!profile.segmentChange) lines.clear()
        }
        summary.onTick(
            movingMs = input.movingMs,
            distanceM = input.distanceM,
            autoPaused = input.autoPaused,
            avgPace = input.avgPace,
            heartRate = input.heartRate,
            profile = profile
        )?.takeIf { it.parts.isNotEmpty() }?.let { lines += it }
        if (!input.settings.enabled) lines.clear()

        val interval = state?.let { s ->
            val target = s.segment?.takeIf { !s.completed }?.target ?: SegmentTarget.None
            // The segments run on the elapsed clock and the pace on the moving one: the same
            // with segments (no auto-pause there), and the cut is never before the window anyway
            val segmentStartMovingMs = (input.movingMs - s.segmentElapsedMs).coerceAtLeast(0L)
            val check = targetCheck.onTick(
                segmentIndex = s.segmentIndex,
                segmentElapsedMs = s.segmentElapsedMs,
                segmentRemainingMs = remainingMs,
                target = target,
                // The verdict's own 60 s window, not the tile's 30 s: half the swing of the noise
                pace = input.livePace?.pace(sinceMovingMs = segmentStartMovingMs, windowMs = LivePaceTracker.VERDICT_WINDOW_MS),
                heartRate = input.heartRate,
                zones = input.zones,
                toleranceSec = input.settings.paceToleranceSec,
                nowMs = input.elapsedMs
            )
            var due = false
            var voiced = false
            check.hint?.let { hint ->
                val voiceSpeaksHints = input.settings.enabled && profile.targetCheck
                when {
                    !voiceSpeaksHints -> due = true
                    lines.isEmpty() && !input.speechBusy -> {
                        lines += hint
                        due = true
                        voiced = true
                    }
                }
                if (due) targetCheck.hintGiven(input.elapsedMs)
            }
            IntervalLive(
                segmentIndex = s.segmentIndex,
                segmentProgress = s.segmentProgress,
                segmentElapsedMs = s.segmentElapsedMs,
                completed = s.completed,
                target = check.state,
                hintDue = due,
                hintVoiced = voiced
            )
        }
        return CoachResult(lines = lines, interval = interval)
    }

    companion object {
        /**
         * The segment at [elapsedMs] and [distanceM] without a tick: a restored workout before
         * its first tick (a paused one has none until the resume). Nothing is consumed (the beep
         * and the warning stay the tick's); a target is pending until the tick checks it.
         */
        fun intervalAt(engine: IntervalEngine, elapsedMs: Long, distanceM: Float): IntervalLive {
            val s = engine.update(elapsedMs, distanceM)
            val hasTarget = !s.completed && s.segment?.target?.let { it != SegmentTarget.None } == true
            return IntervalLive(
                segmentIndex = s.segmentIndex,
                segmentProgress = s.segmentProgress,
                segmentElapsedMs = s.segmentElapsedMs,
                completed = s.completed,
                target = if (hasTarget) TargetState(TargetVerdict.PENDING) else TargetState.NONE
            )
        }
    }
}
