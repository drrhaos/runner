package com.runner.academy.ui.tracking

import com.runner.academy.data.GpsStatus

/** What to say when the session GPS status changes. */
enum class GpsAnnouncement { LOST, UNRELIABLE, RECOVERED }

/**
 * Picks the voice line for a status change. The caller passes the status it last
 * announced (or saw), so each transition is spoken once; a null [previous] (fresh start
 * or service restore) never triggers a warning.
 */
object GpsVoiceTransition {

    fun announcement(current: GpsStatus, previous: GpsStatus?): GpsAnnouncement? {
        if (previous == null || previous == current) return null
        return when {
            current == GpsStatus.LOST -> GpsAnnouncement.LOST
            current == GpsStatus.UNRELIABLE -> GpsAnnouncement.UNRELIABLE
            current == GpsStatus.FOUND && previous == GpsStatus.LOST -> GpsAnnouncement.RECOVERED
            isGood(current) && previous == GpsStatus.UNRELIABLE -> GpsAnnouncement.RECOVERED
            else -> null
        }
    }

    private fun isGood(status: GpsStatus): Boolean = when (status) {
        GpsStatus.FOUND, GpsStatus.STRONG, GpsStatus.MEDIUM, GpsStatus.WEAK -> true
        GpsStatus.SEARCHING, GpsStatus.LOST, GpsStatus.DENIED, GpsStatus.UNRELIABLE -> false
    }
}
