package com.runner.academy.ui.tracking

import com.runner.academy.service.AutoPauseEvent

/** What to say on an auto-pause transition. */
enum class AutoPauseAnnouncement { PAUSED, RESUMED }

/**
 * Picks the voice line for a detector event, like [GpsVoiceTransition]. Only detector events
 * reach it: an auto-pause closed by a manual pause or Stop, and a restore into an open
 * auto-pause, say nothing.
 */
object AutoPauseVoiceTransition {

    /** A shorter stop is not worth a "Resuming". */
    const val SILENT_RESUME_UNDER_MS = 5_000L

    /** @param autoPauseDurationMs The auto-pause just closed, by its backdated bounds. */
    fun announcement(event: AutoPauseEvent, autoPauseDurationMs: Long): AutoPauseAnnouncement? = when (event) {
        is AutoPauseEvent.Pause -> AutoPauseAnnouncement.PAUSED
        is AutoPauseEvent.Resume ->
            if (autoPauseDurationMs >= SILENT_RESUME_UNDER_MS) AutoPauseAnnouncement.RESUMED else null
    }
}
