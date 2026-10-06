package com.runner.academy.service

import com.runner.academy.data.PauseInterval
import com.runner.academy.data.PauseKind
import com.runner.academy.data.SessionClockState
import com.runner.academy.util.TrackPauses

/**
 * The single owner of a session's time.
 *  - elapsed = now − start − Σmanual (the dead-process downtime counts, as before);
 *  - moving = elapsed − Σauto − the open auto-pause.
 *
 * Rules: a manual pause closes an open auto-pause; an auto-pause does not start during a manual
 * pause nor before the last resume; [stop] closes open intervals and freezes both clocks.
 * Calls that break a rule are ignored. Pure: every call takes the time explicitly.
 */
class SessionClock(state: SessionClockState = SessionClockState()) {

    var state: SessionClockState = state
        private set

    val autoPaused: Boolean get() = state.autoPausedAt != null
    val everAutoPaused: Boolean get() = state.everAutoPaused

    fun start(now: Long) {
        state = SessionClockState(startedAt = now)
    }

    fun pauseManual(now: Long) {
        if (state.stoppedAt != null || state.manualPausedAt != null) return
        state = closeAuto(state, now).copy(manualPausedAt = now)
    }

    fun resumeManual(now: Long) {
        val pausedAt = state.manualPausedAt ?: return
        if (state.stoppedAt != null) return
        val end = maxOf(now, pausedAt)
        state = state.copy(
            manualPausedAt = null,
            resumedAt = end,
            pauses = state.pauses + PauseInterval(pausedAt, end, PauseKind.MANUAL)
        )
    }

    /** [at] may lie in the past (the detector backdates it); it is clamped to the last resume. */
    fun enterAutoPause(at: Long) {
        if (state.stoppedAt != null || state.manualPausedAt != null || state.autoPausedAt != null) return
        val start = maxOf(at, state.resumedAt ?: state.startedAt, state.startedAt)
        state = state.copy(autoPausedAt = start, everAutoPaused = true)
    }

    fun exitAutoPause(at: Long) {
        if (state.stoppedAt != null || state.autoPausedAt == null) return
        val closed = closeAuto(state, at)
        state = closed.copy(resumedAt = closed.pauses.last().end)
    }

    fun stop(now: Long) {
        if (state.stoppedAt != null) return
        var next = closeAuto(state, now)
        next.manualPausedAt?.let { pausedAt ->
            next = next.copy(
                manualPausedAt = null,
                pauses = next.pauses + PauseInterval(pausedAt, maxOf(now, pausedAt), PauseKind.MANUAL)
            )
        }
        state = next.copy(stoppedAt = now)
    }

    fun elapsedMs(now: Long): Long {
        val end = state.stoppedAt ?: now
        val openManual = state.manualPausedAt?.let { (end - it).coerceAtLeast(0L) } ?: 0L
        return (end - state.startedAt - state.legacyManualPauseMs - sumOf(PauseKind.MANUAL) - openManual)
            .coerceAtLeast(0L)
    }

    fun movingMs(now: Long): Long {
        val end = state.stoppedAt ?: now
        val openAuto = state.autoPausedAt?.let { (end - it).coerceAtLeast(0L) } ?: 0L
        return (elapsedMs(now) - sumOf(PauseKind.AUTO) - openAuto).coerceAtLeast(0L)
    }

    /** Closed pauses in order; after [stop] that is every pause of the session. */
    fun pauses(): List<PauseInterval> = state.pauses

    private fun sumOf(kind: PauseKind): Long = TrackPauses.totalMs(state.pauses, kind)

    private fun closeAuto(from: SessionClockState, at: Long): SessionClockState {
        val pausedAt = from.autoPausedAt ?: return from
        return from.copy(
            autoPausedAt = null,
            pauses = from.pauses + PauseInterval(pausedAt, maxOf(at, pausedAt), PauseKind.AUTO)
        )
    }
}
