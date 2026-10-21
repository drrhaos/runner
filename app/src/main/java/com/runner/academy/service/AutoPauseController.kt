package com.runner.academy.service

import com.runner.academy.data.GpsStatus

/**
 * An auto-pause transition applied to the session clock.
 * @param announce Speak it (if voice is on); see [AutoPauseController.tick].
 * @param closedAutoPauseMs On a resume, the auto-pause just closed (backdated bounds); 0 on a pause.
 */
data class AutoPauseTransition(
    val event: AutoPauseEvent,
    val announce: Boolean,
    val closedAutoPauseMs: Long
)

/**
 * The tracking service's auto-pause glue between the [AutoPauseDetector] and the session clock
 * ([WorkoutSessionManager]); the bench (`SessionReplay`) runs the same object.
 *
 *  - Applies while the workout runs (not on a manual pause) and [tick] is told it is enabled
 *    (the setting is on and there are no interval segments).
 *  - Disabled during an auto-pause: it ends now, silently. Enabled again: the detector starts
 *    afresh, so nothing seen while it was off backdates a pause.
 *  - A transition the clock refuses resets the detector to the clock's state.
 *  - Restored into an open auto-pause: the first resume is silent (the user did not hear the
 *    pause in this process), later transitions speak.
 *
 * Mono → wall: `wall = nowWall − (nowMono − atMono)`.
 */
class AutoPauseController(cfg: AutoPauseConfig = AutoPauseConfig()) {

    private val detector = AutoPauseDetector(cfg)
    /** False while disabled: the next enabled tick resets the detector. */
    private var armed = false
    private var silentResume = false

    fun onFix(atMono: Long, speedMps: Float?, usable: Boolean) = detector.onFix(atMono, speedMps, usable)

    /**
     * A fresh detector: at the start, on Resume after a manual pause (a new auto-pause then needs
     * a full 10 s), on a restore ([autoPaused]: the open auto-pause of the checkpoint, [restored]).
     */
    fun reset(autoPaused: Boolean = false, restored: Boolean = false) {
        detector.reset(paused = autoPaused)
        armed = true
        silentResume = restored && autoPaused
    }

    /** The 1 s timer's step; null when nothing changed. */
    fun tick(
        manager: WorkoutSessionManager,
        enabled: Boolean,
        steps: Int?,
        nowMono: Long,
        nowWall: Long
    ): AutoPauseTransition? {
        val session = manager.getSession()
        if (!session.isTracking || session.isPaused) return null
        if (!enabled) {
            armed = false
            if (!session.autoPaused) return null
            return apply(manager, AutoPauseEvent.Resume(nowMono), announce = false, nowMono, nowWall)
        }
        if (!armed) reset(autoPaused = session.autoPaused)
        val event = detector.tick(nowMono, steps, isGpsLost(session.gpsStatus)) ?: return null
        val announce = !(event is AutoPauseEvent.Resume && silentResume)
        return apply(manager, event, announce, nowMono, nowWall)
    }

    private fun apply(
        manager: WorkoutSessionManager,
        event: AutoPauseEvent,
        announce: Boolean,
        nowMono: Long,
        nowWall: Long
    ): AutoPauseTransition? {
        val atWall = nowWall - (nowMono - event.atMono)
        val closedMs = when (event) {
            is AutoPauseEvent.Pause -> if (manager.enterAutoPause(atWall, nowWall)) 0L else null
            is AutoPauseEvent.Resume -> manager.exitAutoPause(atWall, nowWall)
        }
        if (event is AutoPauseEvent.Resume) silentResume = false
        if (closedMs == null) {
            // Refused by the clock: follow it rather than drift apart
            detector.reset(paused = manager.getSession().autoPaused)
            return null
        }
        return AutoPauseTransition(event, announce, closedMs)
    }

    companion object {
        /** Lost or denied: no fix will come, so standing cannot be told from a lost signal. */
        fun isGpsLost(status: GpsStatus): Boolean = status == GpsStatus.LOST || status == GpsStatus.DENIED
    }
}
