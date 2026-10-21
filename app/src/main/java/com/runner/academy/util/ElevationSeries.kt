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
     * Fixes without altitude over at most this long are passed over, the line and the count go
     * on across them: some phones leave the altitude out of every few fixes, and breaking at each
     * one cut the track into pieces too short to count any climb (0 m on a hilly run). A longer
     * stretch without altitude breaks the line: the height across it is not known.
     */
    const val MAX_HOLE_MS = 30_000L

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
    private fun barometerOffset(points: List<TrackPoint>): Double =
        points.mapNotNull { point ->
            val gps = point.knownAltitude() ?: return@mapNotNull null
            val baro = point.knownBaroAltitude() ?: return@mapNotNull null
            gps - baro
        }.median() ?: 0.0
}
