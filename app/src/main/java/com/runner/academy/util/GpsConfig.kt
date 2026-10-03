package com.runner.academy.util

import androidx.core.location.LocationRequestCompat
import kotlin.math.abs

/**
 * GPS settings for workout tracking.
 *
 * Dual profile, both delivered live (no batching, see [MAX_UPDATE_DELAY_MS]):
 *  - Screen on: ~1 Hz (live map).
 *  - Screen off: ~2 s base, still dense enough for smooth tracks.
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

    /** Do not request sub-1 Hz bursts from the GNSS chip. */
    const val MIN_UPDATE_INTERVAL = 1000L

    /**
     * No distance filter in the request, screen on or off. Standing at a traffic light the
     * provider would otherwise deliver nothing, the watchdog would flag GPS as lost and the
     * next fix would break the track. Standing jitter is dropped by
     * [com.runner.academy.service.GpsLocationProcessor.MIN_POINT_DISTANCE_METERS] instead.
     */
    const val MIN_DISTANCE = 0f

    /**
     * No batching, screen on or off. On API 31+ the platform GNSS provider switches the chip to
     * hardware batching once maxUpdateDelay >= 2 * interval. With the screen on the chip held
     * fixes long enough for the watchdog to flag GPS as lost; with the screen off (Samsung S22,
     * 20 s window) it stopped delivering for minutes and came back with a cold, jumpy fix.
     * The service holds a wake lock during a workout, so batching would save no wakeups anyway.
     */
    const val MAX_UPDATE_DELAY_MS = 0L

    /** Absolute bearing delta (degrees) that counts as a turn. */
    const val TURN_BEARING_DELTA_DEG = 20f

    /** Below this speed GPS bearing is mostly noise and must not trigger turn densify. */
    const val TURN_MIN_SPEED_MPS = 1.5f

    /** Consecutive straight fixes needed to leave turn densify (hysteresis). */
    const val TURN_RELEASE_FIXES = 3

    /**
     * Adaptive interval from current speed (km/h) and display state.
     * [turning] densifies sampling only with the screen on: with the screen off nobody watches
     * the curve being drawn, and each densify re-registers the request.
     */
    fun getAdaptiveInterval(
        currentSpeed: Float,
        screenInteractive: Boolean,
        turning: Boolean = false
    ): Long {
        if (turning && screenInteractive) return TURN_DENSIFY_INTERVAL
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

    fun createWorkoutLocationRequest(screenInteractive: Boolean = true): LocationRequestCompat {
        return createAdaptiveLocationRequest(
            intervalMs = if (screenInteractive) HIGH_ACCURACY_INTERVAL else SCREEN_OFF_INTERVAL
        )
    }

    /** Screen state only picks [intervalMs]; the request itself is the same on and off screen. */
    fun createAdaptiveLocationRequest(intervalMs: Long): LocationRequestCompat {
        val interval = intervalMs.coerceAtLeast(MIN_UPDATE_INTERVAL)
        return LocationRequestCompat.Builder(interval)
            .setQuality(LocationRequestCompat.QUALITY_HIGH_ACCURACY)
            .setMinUpdateIntervalMillis(minOf(MIN_UPDATE_INTERVAL, interval))
            .setMaxUpdateDelayMillis(MAX_UPDATE_DELAY_MS)
            .setMinUpdateDistanceMeters(MIN_DISTANCE)
            .build()
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

    /**
     * Turn state with hysteresis: enters on a sharp bearing change at running speed,
     * leaves only after [TURN_RELEASE_FIXES] straight fixes — so GPS bearing noise
     * does not flip the location request on every fix.
     */
    class TurnDetector {
        private var lastBearingDeg: Float? = null
        private var straightFixes = 0
        var isTurning: Boolean = false
            private set

        /** Feeds one accepted fix; returns the (possibly unchanged) turn state. */
        fun onFix(bearingDeg: Float?, speedMps: Float): Boolean {
            if (bearingDeg == null || speedMps < TURN_MIN_SPEED_MPS) {
                // Bearing unreliable: do not compare against it later either
                lastBearingDeg = null
                return release()
            }
            val sharp = isTurning(lastBearingDeg, bearingDeg)
            lastBearingDeg = bearingDeg
            if (sharp) {
                isTurning = true
                straightFixes = 0
                return true
            }
            return release()
        }

        fun reset() {
            lastBearingDeg = null
            straightFixes = 0
            isTurning = false
        }

        private fun release(): Boolean {
            if (isTurning && ++straightFixes >= TURN_RELEASE_FIXES) {
                isTurning = false
                straightFixes = 0
            }
            return isTurning
        }
    }

    /** Pre-workout map / readiness — high accuracy so the athlete gets a fix before Start. */
    fun createPreWorkoutLocationRequest(): LocationRequestCompat {
        return LocationRequestCompat.Builder(2000L)
            .setQuality(LocationRequestCompat.QUALITY_HIGH_ACCURACY)
            .setMinUpdateIntervalMillis(MIN_UPDATE_INTERVAL)
            .setMaxUpdateDelayMillis(MAX_UPDATE_DELAY_MS)
            // Standing still must still receive accuracy improvements
            .setMinUpdateDistanceMeters(0f)
            .build()
    }
}
