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
 * The service's once-a-second use of [HeartRateVoiceTransition]: it keeps when the valid values
 * stopped and whether "lost" was said. The count starts with the workout (a strap never put on
 * is "lost" after 30 s too). A manual pause says nothing — the runner often takes the strap off
 * or fixes it at a stop — and after the resume the 30 s count starts again. An auto-pause
 * counts as usual: at a traffic light the strap matters.
 */
class HeartRateVoiceTimer(startMs: Long) {

    private var invalidSinceMs: Long? = startMs
    private var announcedLost = false

    /** @param hasValidData a fresh value now ([com.runner.academy.sensor.HeartRateDisplay.isFresh]). */
    fun onTick(hasValidData: Boolean, nowMs: Long, manualPause: Boolean): HrAnnouncement? {
        if (manualPause) {
            invalidSinceMs = null
            return null
        }
        if (hasValidData) {
            invalidSinceMs = null
        } else if (invalidSinceMs == null) {
            invalidSinceMs = nowMs
        }
        val since = invalidSinceMs?.let { nowMs - it } ?: 0L
        val announcement = HeartRateVoiceTransition.announcement(hasValidData, since, announcedLost)
        when (announcement) {
            HrAnnouncement.LOST -> announcedLost = true
            HrAnnouncement.CONNECTED -> announcedLost = false
            null -> Unit
        }
        return announcement
    }
}
