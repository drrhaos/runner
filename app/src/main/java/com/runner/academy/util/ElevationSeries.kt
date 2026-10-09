package com.runner.academy.util

import com.runner.academy.data.ElevationSource
import com.runner.academy.data.TrackPoint
import com.runner.academy.data.knownAltitude
import com.runner.academy.data.knownBaroAltitude

/**
 * The altitude of every point for a [ElevationSource] — what [ElevationGain] counts and the
 * elevation chart draws, so both read the same values. Null where the point has none.
 */
object ElevationSeries {

    /**
     * GPS and file: the point's [knownAltitude]. Barometer: its [knownBaroAltitude] lifted by
     * the median of (GPS altitude − barometric altitude) over the points that have both, so the
     * uncalibrated pressure altitude reads on a scale like the absolute one (the differences,
     * and so the gain, stay the barometer's); without GPS altitudes it is drawn as it is.
     */
    fun of(points: List<TrackPoint>, source: ElevationSource): List<Double?> = when (source) {
        ElevationSource.BAROMETER -> {
            val offset = barometerOffset(points)
            points.map { point -> point.knownBaroAltitude()?.let { it + offset } }
        }
        ElevationSource.GPS, ElevationSource.FILE, ElevationSource.NONE -> points.map { it.knownAltitude() }
    }

    /** Median of (GPS altitude − barometric altitude) over the points with both; 0 without any. */
    private fun barometerOffset(points: List<TrackPoint>): Double {
        val differences = points.mapNotNull { point ->
            val gps = point.knownAltitude() ?: return@mapNotNull null
            val baro = point.knownBaroAltitude() ?: return@mapNotNull null
            gps - baro
        }.sorted()
        if (differences.isEmpty()) return 0.0
        val mid = differences.size / 2
        return if (differences.size % 2 == 1) differences[mid] else (differences[mid - 1] + differences[mid]) / 2
    }
}
