package com.runner.academy.util

/**
 * The one formula for a workout's `avgPace`, used by every path that writes it (live recording,
 * form, GPX and backup import): moving time over distance, so auto-pauses do not slow it down.
 */
object PaceMath {

    /** Minutes per km from [movingMs] over [distanceKm]; 0 when either is not positive. */
    fun avgPace(distanceKm: Float, movingMs: Long): Float {
        if (!(distanceKm > 0f) || movingMs <= 0L) return 0f
        return (movingMs / SpeedPaceUnits.MS_PER_MINUTE) / distanceKm
    }
}
