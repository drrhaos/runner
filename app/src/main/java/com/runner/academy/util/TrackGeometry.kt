package com.runner.academy.util

import android.location.Location
import com.runner.academy.data.TrackPoint

/**
 * Track geometry: distances, gap detection, derived speeds from point pairs.
 */
object TrackGeometry {

    /**
     * Distance jump treated as a GPS gap when [TrackPoint.afterGap] was not set
     * (e.g. older tracks or missed gap flags). Must stay well above typical sparse
     * GPX / chart-sample spacing (~100–600 m) so legitimate steps are not dropped.
     */
    private const val GAP_DISTANCE_METERS = 2_000f

    fun distanceMeters(point1: TrackPoint, point2: TrackPoint): Float {
        if (!GpsFilter.isValidLatLon(point1.latitude, point1.longitude) ||
            !GpsFilter.isValidLatLon(point2.latitude, point2.longitude)
        ) {
            return 0f
        }
        val loc1 = Location("").apply {
            latitude = point1.latitude
            longitude = point1.longitude
        }
        val loc2 = Location("").apply {
            latitude = point2.latitude
            longitude = point2.longitude
        }
        return loc1.distanceTo(loc2)
    }

    /**
     * True when the step from [prev] to [point] should not contribute distance.
     *
     * Uses the explicit [TrackPoint.afterGap] flag and large teleports only.
     * Do **not** treat moderate timestamp gaps as gaps: sparse GPX tracks often
     * have 30s–few minutes spacing. Live phantom prevention belongs in [GpsFilter].
     */
    fun isTrackGapStep(prev: TrackPoint, point: TrackPoint): Boolean {
        if (point.afterGap) return true
        val stepM = distanceMeters(prev, point)
        return stepM >= GAP_DISTANCE_METERS
    }

    fun totalDistanceMeters(points: List<TrackPoint>): Float {
        if (points.size < 2) return 0f
        var total = 0f
        for (i in 1 until points.size) {
            if (isTrackGapStep(points[i - 1], points[i])) continue
            total += distanceMeters(points[i - 1], points[i])
        }
        return total
    }

    /**
     * Instantaneous speed between two points (m/s) from distance/time —
     * not raw GPS speed. Falls back to GPS speed when dt or distance is zero.
     */
    fun derivedSpeedMs(point1: TrackPoint, point2: TrackPoint): Float {
        val dtSec = (point2.timestamp - point1.timestamp) / 1000f
        if (dtSec <= 0f) {
            return point2.speed?.takeIf { it > 0f } ?: 0f
        }
        val distanceMeters = distanceMeters(point1, point2)
        if (distanceMeters <= 0f) {
            return point2.speed?.takeIf { it > 0f } ?: 0f
        }
        return distanceMeters / dtSec
    }

    fun maxDerivedSpeedMs(points: List<TrackPoint>): Float {
        if (points.size < 2) return 0f
        var maxSpeed = 0f
        for (i in 1 until points.size) {
            if (isTrackGapStep(points[i - 1], points[i])) continue
            val speed = derivedSpeedMs(points[i - 1], points[i])
            if (speed.isFinite() && speed > maxSpeed) maxSpeed = speed
        }
        return maxSpeed
    }
}
