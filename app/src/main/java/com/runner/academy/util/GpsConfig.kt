package com.runner.academy.util

import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.Priority

/**
 * GPS settings for workout tracking.
 *
 * Balance: ~1 Hz while moving keeps curves smooth; walk backs off to 2 s.
 * Still cheaper than the old 0.5–1 s + zero displacement + no batching setup
 * (which kept GNSS nearly always hot).
 */
object GpsConfig {

    /** Preferred fix cadence while jogging. */
    const val HIGH_ACCURACY_INTERVAL = 1000L
    const val MEDIUM_ACCURACY_INTERVAL = 5000L
    const val LOW_ACCURACY_INTERVAL = 10000L

    /** Do not request sub-1 Hz bursts from Fused. */
    const val MIN_UPDATE_INTERVAL = 1000L

    /**
     * Match [com.runner.academy.service.GpsLocationProcessor.MIN_POINT_DISTANCE_METERS]:
     * drop standing jitter, but keep enough points on bends.
     * (5 m + 2–3 s previously made tracks look like long straight chords.)
     */
    const val MIN_DISTANCE = 2f

    /**
     * Adaptive interval from current speed (km/h).
     */
    fun getAdaptiveInterval(currentSpeed: Float): Long {
        return when {
            currentSpeed > 20f -> 1000L // sprint / cycling
            currentSpeed > 5f -> HIGH_ACCURACY_INTERVAL // jogging — dense for curves
            else -> 2000L // walk / brief stops
        }
    }

    fun createWorkoutLocationRequest(): LocationRequest {
        return createAdaptiveLocationRequest(HIGH_ACCURACY_INTERVAL)
    }

    fun createAdaptiveLocationRequest(intervalMs: Long): LocationRequest {
        val interval = intervalMs.coerceAtLeast(MIN_UPDATE_INTERVAL)
        return LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            interval
        ).apply {
            setMinUpdateIntervalMillis(minOf(MIN_UPDATE_INTERVAL, interval))
            // Mild batching when the screen is off; still delivers each computed fix
            setMaxUpdateDelayMillis(interval)
            setWaitForAccurateLocation(false)
            setMinUpdateDistanceMeters(MIN_DISTANCE)
        }.build()
    }

    /** Pre-workout map marker / status — balanced power, not continuous GNSS. */
    fun createStatusLocationRequest(): LocationRequest {
        return LocationRequest.Builder(
            Priority.PRIORITY_BALANCED_POWER_ACCURACY,
            MEDIUM_ACCURACY_INTERVAL
        ).apply {
            setMinUpdateIntervalMillis(MEDIUM_ACCURACY_INTERVAL)
            setMaxUpdateDelayMillis(MEDIUM_ACCURACY_INTERVAL * 2)
            setWaitForAccurateLocation(false)
            setMinUpdateDistanceMeters(10f)
        }.build()
    }
}
