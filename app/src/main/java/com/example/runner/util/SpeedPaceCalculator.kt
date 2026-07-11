package com.example.runner.util

/**
 * Pure-function calculator for speed and pace values.
 * Centralises the math that was duplicated between [WorkoutTrackingService]
 * and [WorkoutTrackingViewModel].
 *
 * @see com.example.runner.service.WorkoutTrackingService
 * @see com.example.runner.ui.tracking.WorkoutTrackingViewModel
 */
object SpeedPaceCalculator {

    /**
     * Compute instantaneous speed in km/h from a single segment.
     *
     * @param distanceDiff Distance of the segment in kilometres.
     * @param timeDiff Milliseconds elapsed for this segment.
     * @return Speed in km/h, or `0f` when inputs are invalid.
     */
    fun computeCurrentSpeed(distanceDiff: Double, timeDiff: Long): Float {
        if (distanceDiff <= 0.0 || timeDiff <= 0L) return 0f
        val hours = timeDiff / (1000f * 3600f)
        if (hours <= 0f) return 0f
        return (distanceDiff / hours).toFloat()
    }

    /**
     * Compute average speed in km/h over the full workout.
     *
     * @param totalDistance Total distance covered in kilometres.
     * @param totalTimeMillis Total active time in milliseconds.
     * @return Average speed in km/h, or `0f` when inputs are invalid.
     */
    fun computeAverageSpeedKmH(totalDistance: Double, totalTimeMillis: Long): Float {
        if (totalDistance <= 0.0 || totalTimeMillis <= 0L) return 0f
        val hours = totalTimeMillis / (1000f * 3600f)
        if (hours <= 0f) return 0f
        return (totalDistance / hours).toFloat()
    }

    /**
     * Compute raw pace in minutes per kilometer from a speed value.
     * Used internally for storage in the session data model.
     *
     * @param speedKmH Speed in km/h.
     * @return Pace in min/km, or `0f` when speed is zero.
     */
    fun computePaceRaw(speedKmH: Float): Float {
        if (speedKmH <= 0f) return 0f
        return 60f / speedKmH
    }

    /**
     * Format pace as "min:sec" per kilometer from a speed value.
     *
     * @param speedKmH Speed in km/h.
     * @return Formatted string like `"5:30"` or `"0:00"` when speed is zero.
     */
    fun computePaceMinPerKm(speedKmH: Float): String {
        if (speedKmH <= 0f) return "0:00"
        val paceMinutes = 60f / speedKmH
        val minutes = paceMinutes.toInt()
        val seconds = ((paceMinutes - minutes) * 60).toInt().coerceIn(0, 59)
        return "%d:%02d".format(minutes, seconds)
    }
}
