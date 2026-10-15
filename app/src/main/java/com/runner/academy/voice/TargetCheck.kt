package com.runner.academy.voice

import com.runner.academy.data.LivePace
import com.runner.academy.data.SegmentTarget
import com.runner.academy.sensor.HeartRateDisplay
import com.runner.academy.util.HrZones
import kotlin.math.abs

/** How the runner is doing against the current segment's target. */
enum class TargetVerdict {
    /** The segment has no target, or the workout is over: no indicator. */
    NO_TARGET,
    /** Neutral: the pace window is not full yet, or the heart rate is still catching up. */
    PENDING,
    /** Neutral and silent: no pace, no fresh heart rate, or the zones are off. */
    NO_DATA,
    IN,
    TOO_SLOW,
    TOO_FAST,
    ABOVE_ZONE,
    BELOW_ZONE;

    /** Off the target: the voice may say it. */
    val isOff: Boolean get() = this == TOO_SLOW || this == TOO_FAST || this == ABOVE_ZONE || this == BELOW_ZONE
}

/**
 * The verdict the indicator shows.
 *
 * @param approximate the pace behind it was counted from steps: shown with «≈», never spoken.
 * @param value what the verdict was made from: the pace of the verdict's window in minutes per
 *   km, or the heart rate in bpm; null without one (no target, pending, no data).
 */
data class TargetState(val verdict: TargetVerdict, val approximate: Boolean = false, val value: Float? = null) {
    companion object {
        val NONE = TargetState(TargetVerdict.NO_TARGET)
    }
}

/**
 * The check of a segment's target (architecture §04), one per workout inside [VoiceCoach]; not
 * checkpointed (a restore starts the cooldown over). Pure: time is the caller's.
 *
 * - **Pace** (target T, tolerance d): in the target within `[T − d, T + d]`; out of it only past
 *   the band by more than [PACE_HYST_SEC]; between the two the verdict it had stays (or "in"
 *   when it had none: a fresh window gets the benefit of the doubt).
 * - **Zone**: in the target within [HrZones.range]; out past a bound by more than
 *   [ZONE_HYST_BPM], the same rule between. Pending for [ZONE_GRACE_MS] of the segment.
 * - **Hint**: an off verdict held without a break for [PERSIST_MS], [HINT_EVERY_MS] after the
 *   last hint given (one cooldown for all the segments), never within [END_QUIET_MS] of the
 *   segment's end, never on a steps pace (only the indicator marks it). Coming back in is silent.
 *   The caller says whether the hint was given ([hintGiven]): one dropped (speech busy, another
 *   line this tick) spends no cooldown and comes due again on the next tick.
 */
class TargetCheck {

    data class Result(val state: TargetState, val hint: VoiceLine.TargetHint?)

    private var segmentIndex = -1
    private var verdict = TargetVerdict.NO_TARGET
    /** When the current off verdict began, for the voice (a steps pace does not count). */
    private var offSinceMs: Long? = null
    private var lastHintMs: Long? = null

    /**
     * @param segmentRemainingMs null: unknown (a distance segment without any pace yet).
     * @param pace the live pace over the verdict's window: the last 60 s
     *   ([com.runner.academy.service.LivePaceTracker.VERDICT_WINDOW_MS]), cut at the segment's start.
     * @param zones null: the zones are off.
     * @param nowMs the workout clock of this tick.
     */
    fun onTick(
        segmentIndex: Int,
        segmentElapsedMs: Long,
        segmentRemainingMs: Long?,
        target: SegmentTarget,
        pace: LivePace?,
        heartRate: HeartRateDisplay?,
        zones: HrZones?,
        toleranceSec: Int,
        nowMs: Long
    ): Result {
        if (segmentIndex != this.segmentIndex) {
            this.segmentIndex = segmentIndex
            verdict = TargetVerdict.NO_TARGET
            offSinceMs = null
        }
        val state = when (target) {
            SegmentTarget.None -> TargetState.NONE
            is SegmentTarget.Pace -> paceState(target, segmentElapsedMs, pace, toleranceSec)
            is SegmentTarget.HrZone -> zoneState(target, segmentElapsedMs, heartRate, zones)
        }
        val previous = verdict
        verdict = state.verdict

        val voiced = state.verdict.isOff && !state.approximate
        offSinceMs = when {
            !voiced -> null
            state.verdict != previous -> nowMs
            else -> offSinceMs ?: nowMs
        }
        val since = offSinceMs
        val last = lastHintMs
        val due = since != null &&
            nowMs - since >= PERSIST_MS &&
            (last == null || nowMs - last >= HINT_EVERY_MS) &&
            (segmentRemainingMs == null || segmentRemainingMs > END_QUIET_MS)
        val hint = if (due) {
            VoiceLine.TargetHint(
                verdict = state.verdict,
                pace = state.value.takeIf { target is SegmentTarget.Pace },
                bpm = state.value?.toInt().takeIf { target is SegmentTarget.HrZone }
            )
        } else {
            null
        }
        return Result(state, hint)
    }

    /** The hint of this tick was given (spoken, or the voice is off and the screen took it). */
    fun hintGiven(nowMs: Long) {
        lastHintMs = nowMs
    }

    private fun paceState(target: SegmentTarget.Pace, elapsedMs: Long, pace: LivePace?, toleranceSec: Int): TargetState {
        if (pace == null) {
            return TargetState(if (elapsedMs < PACE_PENDING_MS) TargetVerdict.PENDING else TargetVerdict.NO_DATA)
        }
        // Seconds per km; positive: slower than the target
        val diff = (pace.minPerKm - target.minPerKm) * 60f
        // A float's minutes are not exact seconds: 4:43 against 4:30 is 13.00001
        val band = toleranceSec + SEC_EPSILON
        val out = toleranceSec + PACE_HYST_SEC + SEC_EPSILON
        val verdict = when {
            abs(diff) <= band -> TargetVerdict.IN
            diff > out -> TargetVerdict.TOO_SLOW
            diff < -out -> TargetVerdict.TOO_FAST
            diff > 0f && verdict == TargetVerdict.TOO_SLOW -> TargetVerdict.TOO_SLOW
            diff < 0f && verdict == TargetVerdict.TOO_FAST -> TargetVerdict.TOO_FAST
            else -> TargetVerdict.IN
        }
        return TargetState(verdict, pace.approximate, pace.minPerKm)
    }

    private fun zoneState(target: SegmentTarget.HrZone, elapsedMs: Long, heartRate: HeartRateDisplay?, zones: HrZones?): TargetState {
        if (zones == null) return TargetState(TargetVerdict.NO_DATA)
        if (elapsedMs < ZONE_GRACE_MS) return TargetState(TargetVerdict.PENDING)
        val bpm = heartRate?.takeIf { it.isFresh }?.bpm ?: return TargetState(TargetVerdict.NO_DATA)
        val range = zones.range(target.zone)
        val verdict = when {
            bpm in range -> TargetVerdict.IN
            bpm > range.last + ZONE_HYST_BPM -> TargetVerdict.ABOVE_ZONE
            bpm < range.first - ZONE_HYST_BPM -> TargetVerdict.BELOW_ZONE
            bpm > range.last && verdict == TargetVerdict.ABOVE_ZONE -> TargetVerdict.ABOVE_ZONE
            bpm < range.first && verdict == TargetVerdict.BELOW_ZONE -> TargetVerdict.BELOW_ZONE
            else -> TargetVerdict.IN
        }
        return TargetState(verdict, approximate = false, value = bpm.toFloat())
    }

    companion object {
        /**
         * A pace verdict leaves "in" only this far past the band, seconds per km (owner's decision
         * after the bench, 2026-10-15). Path noise makes the GPS pace read ~2.5 s per km fast and
         * swing ~5 s per km (1σ) over 30 s, correlated over ~20 s; so the verdict takes a 60 s
         * window (~3 s per km) and a hint comes past ≈ 17 s per km off a ±10 target. With 30 s
         * and 3–5 s an even run 5 s off the target heard up to two false hints in 10 min; see
         * `TargetReplayTest`.
         */
        const val PACE_HYST_SEC = 7f
        /** A zone verdict leaves "in" only this far past a bound, bpm. */
        const val ZONE_HYST_BPM = 2
        /**
         * No pace this early in a segment is pending, not no data: the window is not full yet. It
         * needs 15 s ([com.runner.academy.service.LivePaceTracker.MIN_SPAN_MS]) from its first
         * tick, up to a second after the start; the rest is slack, so "no pace" never flickers.
         */
        const val PACE_PENDING_MS = 20_000L
        /** The heart rate lags the effort: a zone is pending this long into its segment. */
        const val ZONE_GRACE_MS = 45_000L
        /** An off verdict is held this long before the voice says it; 15 s, not the planned 10, for the noise as above. */
        const val PERSIST_MS = 15_000L
        /** One hint at most this often, over all the segments. */
        const val HINT_EVERY_MS = 30_000L
        /** No hint this close to the segment's end. */
        const val END_QUIET_MS = 10_000L
        private const val SEC_EPSILON = 0.01f
    }
}
