package com.runner.academy.data

/**
 * Persisted state of a [com.runner.academy.service.SessionClock] (checkpoint, live session).
 * Wall-clock ms; every field has a default so Gson builds it through the no-arg constructor and
 * an older or partial JSON reads.
 */
data class SessionClockState(
    /** Start of the session; 0 before the clock starts. */
    val startedAt: Long = 0L,
    /** The moment of Stop; null while the session runs. */
    val stoppedAt: Long? = null,
    /** Start of the open manual pause, if any. */
    val manualPausedAt: Long? = null,
    /** Start of the open auto-pause, if any. */
    val autoPausedAt: Long? = null,
    /** Last manual resume or auto-pause exit: an auto-pause never starts before it. */
    val resumedAt: Long? = null,
    /** Closed pauses, in order. */
    val pauses: List<PauseInterval> = emptyList(),
    /** Manual pause time of a checkpoint written before the clock existed (no intervals known). */
    val legacyManualPauseMs: Long = 0L,
    val everAutoPaused: Boolean = false
)
