package com.runner.academy.util

import android.location.Location
import android.util.Log
import org.osmdroid.util.GeoPoint
import kotlin.math.abs
import kotlin.math.max

/**
 * Утилита для фильтрации GPS выбросов и неправильных координат.
 * Пороги намеренно мягкие: городской GPS часто даёт шум по accuracy/altitude/speed.
 */
object GpsFilter {

    /** After this silence between fixes, the next valid point is treated as gap resume (no phantom distance). */
    const val GAP_RESUME_THRESHOLD_MS = 20_000L

    /** Absolute jump that is never plausible between consecutive accepted fixes. */
    private const val MAX_DISTANCE_BETWEEN_POINTS = 800.0

    /** Points worse than this are ignored (urban GPS often reports 30–80 m). */
    private const val MAX_ACCEPTABLE_ACCURACY = 150.0

    /**
     * Instantaneous [Location.getSpeed] spikes are common and unreliable —
     * only reject absurd values (≈180 km/h), not jogging/sprint noise.
     */
    private const val MAX_REPORTED_SPEED_MPS = 50.0

    /** Default max speed between points (~68 km/h) when workout type is not provided. */
    private const val DEFAULT_MAX_REASONABLE_SPEED_MPS = 19.0

    /** Allow GPS jitter above the workout speed cap. */
    private const val SPEED_MARGIN = 1.75

    /**
     * GPS altitude is noisy; only reject extreme cliffs between nearby fixes.
     * Prefer rate of climb over absolute delta when time is known.
     */
    private const val MAX_ALTITUDE_CHANGE = 200.0
    private const val MAX_VERTICAL_SPEED_MPS = 8.0

    /**
     * True when the new fix should re-anchor the track after a GPS outage
     * (long time gap and/or explicit [forceGapResume] from LOST status).
     */
    fun isGapResume(
        previousLocation: Location?,
        newLocation: Location,
        forceGapResume: Boolean = false
    ): Boolean {
        if (forceGapResume && previousLocation != null) return true
        if (previousLocation == null) return false
        val timeDiff = newLocation.time - previousLocation.time
        return timeDiff >= GAP_RESUME_THRESHOLD_MS
    }

    /**
     * Проверяет, является ли GPS координата валидной
     */
    fun isValidGpsLocation(location: Location): Boolean {
        Log.d(
            "GpsFilter",
            "Validating GPS location: lat=${location.latitude}, lon=${location.longitude}, " +
                "accuracy=${if (location.hasAccuracy()) location.accuracy else "N/A"}m, " +
                "speed=${if (location.hasSpeed()) location.speed else "N/A"}m/s"
        )

        if (!isValidCoordinate(location.latitude, location.longitude)) {
            Log.w("GpsFilter", "Invalid coordinates: lat=${location.latitude}, lon=${location.longitude}")
            return false
        }

        if (location.hasAccuracy() && location.accuracy > MAX_ACCEPTABLE_ACCURACY) {
            Log.w(
                "GpsFilter",
                "GPS accuracy too poor: ${location.accuracy}m > ${MAX_ACCEPTABLE_ACCURACY}m"
            )
            return false
        }

        // Ignore mild reported-speed spikes; only drop absurd values
        if (location.hasSpeed() && location.speed > MAX_REPORTED_SPEED_MPS) {
            Log.w(
                "GpsFilter",
                "Reported speed absurd: ${location.speed}m/s > ${MAX_REPORTED_SPEED_MPS}m/s"
            )
            return false
        }

        Log.d("GpsFilter", "GPS location validation passed")
        return true
    }

    private fun isValidCoordinate(latitude: Double, longitude: Double): Boolean {
        return latitude in -90.0..90.0 &&
            longitude in -180.0..180.0 &&
            !latitude.isNaN() &&
            !longitude.isNaN() &&
            latitude.isFinite() &&
            longitude.isFinite()
    }

    /**
     * Фильтрует GPS выбросы, сравнивая с предыдущей точкой.
     *
     * When [forceGapResume] is true or the time since [previousLocation] exceeds
     * [GAP_RESUME_THRESHOLD_MS], speed/distance outlier checks against the previous
     * point are skipped so the first fix after a tunnel/indoor gap is accepted as
     * a new anchor (caller must not add segment distance across the gap).
     */
    fun filterGpsOutlier(
        newLocation: Location,
        previousLocation: Location?,
        maxReasonableSpeedMps: Float = DEFAULT_MAX_REASONABLE_SPEED_MPS.toFloat(),
        forceGapResume: Boolean = false
    ): Location? {
        if (previousLocation == null) {
            return if (isValidGpsLocation(newLocation)) {
                Log.d("GpsFilter", "First valid GPS point accepted")
                newLocation
            } else {
                Log.w("GpsFilter", "First GPS point rejected")
                null
            }
        }

        if (!isValidGpsLocation(newLocation)) {
            Log.w("GpsFilter", "GPS point failed basic validation")
            return null
        }

        if (newLocation.time < previousLocation.time) {
            Log.w("GpsFilter", "GPS point with earlier timestamp rejected")
            return null
        }

        if (isGapResume(previousLocation, newLocation, forceGapResume)) {
            Log.d(
                "GpsFilter",
                "Gap resume accepted: dt=${newLocation.time - previousLocation.time}ms, force=$forceGapResume"
            )
            return newLocation
        }

        val distance = newLocation.distanceTo(previousLocation).toDouble()
        if (distance > MAX_DISTANCE_BETWEEN_POINTS) {
            Log.w(
                "GpsFilter",
                "GPS outlier detected: distance=${distance}m > ${MAX_DISTANCE_BETWEEN_POINTS}m"
            )
            return null
        }

        val timeDiffMs = newLocation.time - previousLocation.time
        val timeDiffSeconds = timeDiffMs / 1000.0
        val speedCap = maxReasonableSpeedMps * SPEED_MARGIN

        if (timeDiffSeconds > 0) {
            val calculatedSpeed = distance / timeDiffSeconds
            if (calculatedSpeed > speedCap) {
                Log.w(
                    "GpsFilter",
                    "GPS outlier detected: calculated speed=${calculatedSpeed}m/s " +
                        "(${calculatedSpeed * 3.6}km/h) > ${speedCap}m/s, " +
                        "distance=${distance}m, time=${timeDiffSeconds}s"
                )
                return null
            }
        }

        if (newLocation.hasAltitude() && previousLocation.hasAltitude()) {
            val altitudeChange = abs(newLocation.altitude - previousLocation.altitude)
            val verticalTooFast = timeDiffSeconds > 0 &&
                (altitudeChange / timeDiffSeconds) > MAX_VERTICAL_SPEED_MPS
            val absoluteTooLarge = altitudeChange > MAX_ALTITUDE_CHANGE
            if (verticalTooFast && absoluteTooLarge) {
                Log.w(
                    "GpsFilter",
                    "GPS outlier detected: altitude change=${altitudeChange}m " +
                        "(from ${previousLocation.altitude}m to ${newLocation.altitude}m)"
                )
                return null
            }
        }

        val expectedMaxDistance = calculateExpectedMaxDistance(
            previousLocation,
            newLocation,
            timeDiffMs,
            maxReasonableSpeedMps
        )
        if (distance > expectedMaxDistance) {
            Log.w(
                "GpsFilter",
                "GPS point exceeds expected distance: ${distance}m > ${expectedMaxDistance}m"
            )
            return null
        }

        Log.d(
            "GpsFilter",
            "GPS point accepted: distance=${distance}m, accuracy=${newLocation.accuracy}m"
        )
        return newLocation
    }

    /**
     * Expected travel distance with a floor based on workout max speed
     * (never collapses to ~0 when previous reported speed is 0 / missing).
     */
    private fun calculateExpectedMaxDistance(
        previousLocation: Location,
        newLocation: Location,
        timeDiffMs: Long,
        maxReasonableSpeedMps: Float
    ): Double {
        val timeDiffSeconds = max(timeDiffMs / 1000.0, 0.5)
        val reported = if (previousLocation.hasSpeed()) previousLocation.speed.toDouble() else 0.0
        // Prefer workout cap with margin; reported speed only raises the allowance
        val speedCap = max(reported * 1.5, maxReasonableSpeedMps * SPEED_MARGIN.toDouble())
        val accuracySlack =
            (if (previousLocation.hasAccuracy()) previousLocation.accuracy else 0f) +
                (if (newLocation.hasAccuracy()) newLocation.accuracy else 0f)
        // Extra slack for typical GPS horizontal jitter
        return speedCap * timeDiffSeconds + accuracySlack + 25.0
    }

    fun createValidGeoPoint(location: Location): GeoPoint? {
        return GeoPoint(location.latitude, location.longitude)
    }

    fun createValidGeoPointWithFiltering(
        location: Location,
        previousLocation: Location?
    ): GeoPoint? {
        val filteredLocation = filterGpsOutlier(location, previousLocation)
        return filteredLocation?.let {
            GeoPoint(it.latitude, it.longitude)
        }
    }

    fun logGpsInfo(location: Location, isAccepted: Boolean) {
        Log.d(
            "GpsFilter",
            "GPS Point: lat=${location.latitude}, lon=${location.longitude}, " +
                "accuracy=${location.accuracy}m, speed=${location.speed}m/s, " +
                "accepted=$isAccepted"
        )
    }
}
