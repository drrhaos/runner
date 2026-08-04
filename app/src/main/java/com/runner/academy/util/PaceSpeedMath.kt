package com.runner.academy.util

/**
 * Pace and speed formulas (pure math, no track geometry).
 */
object PaceSpeedMath {

    fun paceMinPerKmFromSpeedMs(speedMs: Float): Float {
        return paceFromSpeedKmh(SpeedPaceUnits.speedMsToKmh(speedMs))
    }

    fun paceFromSpeedKmh(speedKmh: Float): Float {
        return if (speedKmh > 0f) 60f / speedKmh else 0f
    }

    fun paceMinPerKmToMinPerMile(paceMinPerKm: Float): Float {
        if (paceMinPerKm <= 0f) return 0f
        return paceMinPerKm * SpeedPaceUnits.KM_PER_MILE
    }

    fun segmentPace(durationSeconds: Double, distanceMeters: Double): Float {
        if (distanceMeters <= 0.0 || durationSeconds <= 0.0) return 0f
        val durationMinutes = durationSeconds / 60.0
        val distanceKm = distanceMeters / 1000.0
        return (durationMinutes / distanceKm).toFloat()
    }

    fun segmentPaceMetric(durationMs: Long, distanceKm: Float, metric: Boolean): Float {
        if (distanceKm <= 0f || durationMs <= 0L) return 0f
        val durationMinutes = durationMs / SpeedPaceUnits.MS_PER_MINUTE
        return if (metric) {
            durationMinutes / distanceKm
        } else {
            durationMinutes / SpeedPaceUnits.kmToMiles(distanceKm)
        }
    }

    /**
     * Overall average pace: totalDuration / totalDistance (min/km).
     * Uses totals — NOT avgDuration / avgDistance.
     */
    fun overallAveragePace(totalDistanceMeters: Double, totalDurationSeconds: Double): Float {
        if (totalDistanceMeters <= 0.0 || totalDurationSeconds <= 0.0) return 0f
        val totalDistanceKm = totalDistanceMeters / 1000.0
        val totalDurationMinutes = totalDurationSeconds / 60.0
        return (totalDurationMinutes / totalDistanceKm).toFloat()
    }

    fun averageSpeedKmh(distanceKm: Float, durationMs: Long): Float {
        if (distanceKm <= 0f || durationMs <= 0L) return 0f
        return distanceKm / (durationMs / SpeedPaceUnits.MS_PER_HOUR)
    }

    fun averageSpeedMs(totalDistanceMeters: Float, totalDurationMs: Long): Float {
        if (totalDistanceMeters <= 0f || totalDurationMs <= 0L) return 0f
        return totalDistanceMeters / (totalDurationMs / 1000f)
    }

    fun segmentSpeed(durationSeconds: Double, distanceMeters: Double): Float {
        if (distanceMeters <= 0.0 || durationSeconds <= 0.0) return 0f
        val distanceKm = distanceMeters / 1000.0
        val hours = durationSeconds / 3600.0
        return (distanceKm / hours).toFloat()
    }

    fun segmentSpeedMetric(durationMs: Long, distanceKm: Float, metric: Boolean): Float {
        if (distanceKm <= 0f || durationMs <= 0L) return 0f
        val speedKmh = distanceKm / (durationMs / SpeedPaceUnits.MS_PER_HOUR)
        return if (metric) speedKmh else SpeedPaceUnits.kmhToMph(speedKmh)
    }

    /** Whole minutes and seconds (0–59) for display. */
    fun paceToMinutesSeconds(paceMinutes: Float): Pair<Int, Int> {
        if (paceMinutes <= 0f) return 0 to 0
        val totalSeconds = (paceMinutes * 60f).toInt().coerceAtLeast(0)
        return totalSeconds / 60 to (totalSeconds % 60)
    }

    /** Formats pace (decimal minutes) as m:ss, e.g. 6.1167 → "6:07". */
    fun formatPaceMmSs(paceMinutes: Float): String {
        if (paceMinutes <= 0f) return "--:--"
        val totalSeconds = (paceMinutes * 60f).toInt().coerceAtLeast(0)
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%d:%02d", minutes, seconds)
    }

    // ---- Live-session helpers (WorkoutSessionManager) -----------------------

    fun computeCurrentSpeed(distanceDiff: Double, timeDiff: Long): Float {
        if (distanceDiff <= 0.0 || timeDiff <= 0L) return 0f
        val hours = timeDiff / (1000f * 3600f)
        if (hours <= 0f) return 0f
        return (distanceDiff / hours).toFloat()
    }

    fun computeAverageSpeedKmH(totalDistance: Double, totalTimeMillis: Long): Float {
        if (totalDistance <= 0.0 || totalTimeMillis <= 0L) return 0f
        val hours = totalTimeMillis / (1000f * 3600f)
        if (hours <= 0f) return 0f
        return (totalDistance / hours).toFloat()
    }

    fun computePaceRaw(speedKmH: Float): Float = paceFromSpeedKmh(speedKmH)

    fun computePaceMinPerKm(speedKmH: Float): String {
        if (speedKmH <= 0f) return "0:00"
        val paceMinutes = 60f / speedKmH
        val minutes = paceMinutes.toInt()
        val seconds = ((paceMinutes - minutes) * 60).toInt().coerceIn(0, 59)
        return "%d:%02d".format(minutes, seconds)
    }
}
