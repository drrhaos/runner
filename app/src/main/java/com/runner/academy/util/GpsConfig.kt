package com.runner.academy.util

import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.Priority
import kotlin.math.abs

/**
 * GPS settings for workout tracking.
 *
 * Dual profile:
 *  - Screen on: ~1 Hz, short delivery delay (live map).
 *  - Screen off: ~2 s base, long [maxUpdateDelay] batching to cut wakeups
 *    while still collecting dense enough points for smooth tracks.
 * Turns temporarily densify sampling so curves stay smooth.
 */
object GpsConfig {

    /** Preferred fix cadence while jogging with the display on. */
    const val HIGH_ACCURACY_INTERVAL = 1000L
    const val MEDIUM_ACCURACY_INTERVAL = 5000L
    const val LOW_ACCURACY_INTERVAL = 10000L

    /** Screen-off base cadence (still dense enough at jogging pace). */
    const val SCREEN_OFF_INTERVAL = 2000L

    /** Temporary cadence when heading changes sharply. */
    const val TURN_DENSIFY_INTERVAL = 1000L

    /** Do not request sub-1 Hz bursts from Fused. */
    const val MIN_UPDATE_INTERVAL = 1000L

    /**
     * Match [com.runner.academy.service.GpsLocationProcessor.MIN_POINT_DISTANCE_METERS]
     * for screen-on. Slightly higher when screen-off to drop standing jitter.
     */
    const val MIN_DISTANCE = 2f
    const val MIN_DISTANCE_SCREEN_OFF = 3f

    /** Batch delivery window while display is off (reduces app wakeups). */
    const val SCREEN_OFF_MAX_UPDATE_DELAY_MS = 20_000L

    /** Short delay while display is on so the map stays live. */
    const val SCREEN_ON_MAX_UPDATE_DELAY_MS = 2_000L

    /** Absolute bearing delta (degrees) that counts as a turn. */
    const val TURN_BEARING_DELTA_DEG = 20f

    /**
     * Adaptive interval from current speed (km/h) and display state.
     * [turning] forces denser sampling so curves stay smooth when screen-off.
     */
    fun getAdaptiveInterval(
        currentSpeed: Float,
        screenInteractive: Boolean,
        turning: Boolean = false
    ): Long {
        if (turning) return TURN_DENSIFY_INTERVAL
        return if (screenInteractive) {
            when {
                currentSpeed > 20f -> HIGH_ACCURACY_INTERVAL
                currentSpeed > 5f -> HIGH_ACCURACY_INTERVAL
                else -> 2000L
            }
        } else {
            when {
                currentSpeed > 20f -> HIGH_ACCURACY_INTERVAL
                currentSpeed > 5f -> SCREEN_OFF_INTERVAL
                else -> 3000L
            }
        }
    }

    /** @deprecated Prefer [getAdaptiveInterval] with screen / turn flags. */
    fun getAdaptiveInterval(currentSpeed: Float): Long =
        getAdaptiveInterval(currentSpeed, screenInteractive = true, turning = false)

    fun createWorkoutLocationRequest(screenInteractive: Boolean = true): LocationRequest {
        return createAdaptiveLocationRequest(
            intervalMs = if (screenInteractive) HIGH_ACCURACY_INTERVAL else SCREEN_OFF_INTERVAL,
            screenInteractive = screenInteractive
        )
    }

    fun createAdaptiveLocationRequest(
        intervalMs: Long,
        screenInteractive: Boolean = true
    ): LocationRequest {
        val interval = intervalMs.coerceAtLeast(MIN_UPDATE_INTERVAL)
        val minDistance = if (screenInteractive) MIN_DISTANCE else MIN_DISTANCE_SCREEN_OFF
        val maxDelay = if (screenInteractive) {
            SCREEN_ON_MAX_UPDATE_DELAY_MS.coerceAtLeast(interval)
        } else {
            SCREEN_OFF_MAX_UPDATE_DELAY_MS.coerceAtLeast(interval * 2)
        }
        return LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            interval
        ).apply {
            setMinUpdateIntervalMillis(minOf(MIN_UPDATE_INTERVAL, interval))
            setMaxUpdateDelayMillis(maxDelay)
            setWaitForAccurateLocation(false)
            setMinUpdateDistanceMeters(minDistance)
        }.build()
    }

    /** Absolute smallest angle between two bearings in degrees [0, 180]. */
    fun bearingDeltaDegrees(fromDeg: Float, toDeg: Float): Float {
        var delta = abs(toDeg - fromDeg) % 360f
        if (delta > 180f) delta = 360f - delta
        return delta
    }

    fun isTurning(previousBearingDeg: Float?, currentBearingDeg: Float): Boolean {
        if (previousBearingDeg == null) return false
        return bearingDeltaDegrees(previousBearingDeg, currentBearingDeg) >= TURN_BEARING_DELTA_DEG
    }

    /** Pre-workout map / readiness — high accuracy so the athlete gets a fix before Start. */
    fun createPreWorkoutLocationRequest(): LocationRequest {
        return LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            2000L
        ).apply {
            setMinUpdateIntervalMillis(MIN_UPDATE_INTERVAL)
            setMaxUpdateDelayMillis(2000L)
            setWaitForAccurateLocation(false)
            // Standing still must still receive accuracy improvements
            setMinUpdateDistanceMeters(0f)
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
