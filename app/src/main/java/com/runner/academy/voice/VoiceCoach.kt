package com.runner.academy.voice

import com.runner.academy.sensor.HeartRateDisplay
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
    val engine: IntervalEngine?
)

data class CoachResult(
    /** In the order to say them. */
    val lines: List<VoiceLine>,
    /** The segments' state after this tick; null: a free run. */
    val interval: IntervalEngine.State?
)

/**
 * Decides what the voice says on each 1 s tick of a running workout (not on a manual pause:
 * the timer stops). Pure: the service and the bench feed it and send the lines to a
 * [VoiceOutput]. One per workout, not checkpointed: after a restore [resume] keeps the summary
 * from speaking for the past, and the checkpointed cursor of the [IntervalEngine] keeps the beep
 * and the warning from repeating.
 *
 * - The segments advance and their beep and warning are consumed on every tick, voice on or off:
 *   the cursor goes to the checkpoint and switching the voice on does not replay them.
 * - Order within a tick: [VoiceLine.SegmentStart] → [VoiceLine.SegmentUpcoming] →
 *   [VoiceLine.Summary]. Status lines are the service's own: they are events and speak at once.
 * - The profile is the interval one when the workout has segments.
 */
class VoiceCoach {

    private val summary = SummaryClock()

    /** Continues a restored workout from this position. */
    fun resume(movingMs: Long, distanceM: Float) {
        summary.resume(movingMs, distanceM)
    }

    fun onTick(input: CoachInput): CoachResult {
        val engine = input.engine
        val profile = input.settings.profileFor(hasSegments = engine != null)
        val lines = mutableListOf<VoiceLine>()

        val state = engine?.update(input.elapsedMs, input.distanceM)
        if (engine != null && state != null) {
            if (engine.consumeSegmentAnnouncement(state)) lines += VoiceLine.SegmentStart
            val remainingMs = engine.estimateRemainingMs(state, input.avgPace)
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

        return CoachResult(lines = if (input.settings.enabled) lines else emptyList(), interval = state)
    }
}
