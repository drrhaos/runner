package com.runner.academy.ui.tracking

/** What to say about the heart-rate sensor. */
enum class HrAnnouncement { LOST, CONNECTED }

/**
 * Picks the voice line for the heart-rate sensor, like [GpsVoiceTransition]:
 * - "lost" after [LOST_AFTER_MS] in a row without a valid value, whatever the reason (the
 *   link dropped, Bluetooth off, the strap taken off). Short drops of 2–10 s (a hand over the
 *   phone, an auto connect) stay silent: BLE drops more often than GPS.
 * - "connected" only after a "lost": the first connection after a start or a restore is silent.
 */
object HeartRateVoiceTransition {

    const val LOST_AFTER_MS = 30_000L

    /**
     * @param sinceMs how long there has been no valid value (ignored while there is one).
     * @param announcedLost "lost" was said and no "connected" since.
     */
    fun announcement(hasValidData: Boolean, sinceMs: Long, announcedLost: Boolean): HrAnnouncement? = when {
        hasValidData -> if (announcedLost) HrAnnouncement.CONNECTED else null
        !announcedLost && sinceMs >= LOST_AFTER_MS -> HrAnnouncement.LOST
        else -> null
    }
}

/**
 * The service's once-a-second use of [HeartRateVoiceTransition]. One per workout, and a new one
 * after a restore.
 *
 * - The 30 s count runs from the time of the last valid sample.
 * - It is armed only by the first valid sample of this workout (since the restore): a strap that
 *   is paired but never worn — or a sensor the system refused — is never announced, and the
 *   first connection is always silent.
 * - A manual pause says nothing (the runner often takes the strap off or fixes it at a stop);
 *   after the resume the 30 s count starts again from the resume, unless a value came since.
 * - An auto-pause counts as usual: at a traffic light the strap matters.
 */
class HeartRateVoiceTimer {

    private var announcedLost = false
    private var paused = false
    /** The resume after the last manual pause: the count runs from there at the earliest. */
    private var resumedAtMs: Long? = null

    /**
     * @param lastValidAtMs the time of the newest valid sample of this workout (same clock as
     *   [nowMs]); null before the first one.
     */
    fun onTick(lastValidAtMs: Long?, nowMs: Long, manualPause: Boolean): HrAnnouncement? {
        if (manualPause) {
            paused = true
            return null
        }
        if (paused) {
            paused = false
            resumedAtMs = nowMs
        }
        val lastValid = lastValidAtMs ?: return null
        val hasValidData = nowMs - lastValid < HeartRateVoiceTransition.LOST_AFTER_MS
        val since = nowMs - maxOf(lastValid, resumedAtMs ?: lastValid)
        val announcement = HeartRateVoiceTransition.announcement(hasValidData, since, announcedLost)
        when (announcement) {
            HrAnnouncement.LOST -> announcedLost = true
            HrAnnouncement.CONNECTED -> announcedLost = false
            null -> Unit
        }
        return announcement
    }
}
