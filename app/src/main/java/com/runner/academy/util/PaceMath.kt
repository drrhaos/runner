package com.runner.academy.util

/**
 * The one formula for an average pace: a workout's `avgPace` on every path that writes it (live
 * recording, form, GPX and backup import) and the averages over many workouts (Σdistance over
 * Σmoving time). Moving time, so auto-pauses do not slow it down.
 */
object PaceMath {

    /** Minutes per km from [movingMs] over [distanceKm]; 0 when either is not positive. */
    fun avgPace(distanceKm: Float, movingMs: Long): Float {
        // Negated so that a NaN distance (every comparison false) also gives 0
        if (!(distanceKm > 0f) || movingMs <= 0L) return 0f
        return (movingMs / SpeedPaceUnits.MS_PER_MINUTE) / distanceKm
    }
}
