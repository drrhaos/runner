package com.runner.academy.util

import com.runner.academy.data.ElevationSource
import com.runner.academy.data.TrackPoint
import com.runner.academy.data.knownAltitude
import kotlin.math.abs

/** Elevation gain and loss of a track, metres. */
data class ElevationResult(val gainM: Float, val lossM: Float)

/**
 * Parameters of [ElevationGain], tuned on the replay bench (`ElevationReplayTest`): a hill of
 * known height within 15 %, a flat 5 km within 15 m.
 *
 * @param outlierHalfWindowMs the Hampel window around a point, each side
 * @param outlierMadFactor a point further than this many MADs from the window median is false
 * @param outlierMinCutoffM ...but never closer than this (a flat window has a MAD of ~0)
 * @param smoothingWindowMs width of the centred moving average
 * @param hysteresisM a turn counts only once the smoothed altitude moved this far back
 */
data class ElevationConfig(
    val outlierHalfWindowMs: Long = 15_000L,
    val outlierMadFactor: Double = 3.0,
    val outlierMinCutoffM: Double = 15.0,
    val smoothingWindowMs: Long,
    val hysteresisM: Double
) {
    companion object {
        /**
         * GPS altitude: noise of 10–20 m between fixes, so a long average and a wide band.
         * Tuned on the bench (AR(1) noise σ 2.5 m, i.e. ±5 m): the spec's starting 20 s / 5 m
         * counted up to 50 m on a flat 5 km; 60 s / 10 m gives 0 m there and a 3 × 40 m hill
         * within 10 %. A climb is still counted in full (the band only delays a turn).
         */
        val GPS = ElevationConfig(smoothingWindowMs = 60_000L, hysteresisM = 10.0)

        /**
         * Barometric altitude: noise well under a metre, so a short average and a narrow band
         * (the spec's 5 s / 1.5 m). The sensor drift of ~8 m/h is slow against the band; the
         * outlier filter is the GPS one (a 15 m floor never touches a real climb).
         */
        val BAROMETER = ElevationConfig(smoothingWindowMs = 5_000L, hysteresisM = 1.5)

        /** A file's `<ele>` may be anything, so it is treated like GPS. */
        fun forSource(source: ElevationSource): ElevationConfig = when (source) {
            ElevationSource.BAROMETER -> BAROMETER
            ElevationSource.GPS, ElevationSource.FILE, ElevationSource.NONE -> GPS
        }
    }
}

/**
 * Elevation gain and loss from the point altitudes of the source ([ElevationSeries]: the GPS
 * or file altitude, the barometric one for the barometer). Per continuous piece of the track
 * (cut at every [TrackPoint.afterGap] and at every point without an altitude — for GPS
 * [knownAltitude]: null or the old 0.0: a gap, a bridge or a missing altitude has no reliable
 * height, so the climb across it is not counted; the tail and a lead-in have no points at all):
 *  1. false fixes out — Hampel filter: a point further than max(k·MAD, floor) from the median
 *     of its time window is replaced by that median;
 *  2. a centred moving average over time;
 *  3. hysteresis: the anchor follows the climb (or descent) under way and a turn counts only
 *     once the altitude went [ElevationConfig.hysteresisM] back from it; before the first
 *     turn the climb starts at the piece's lowest value and the descent at its highest. The
 *     anchor is the piece's own, so nothing is counted across a break.
 */
object ElevationGain {

    /** Null: no point has a known altitude. */
    fun compute(
        points: List<TrackPoint>,
        source: ElevationSource,
        cfg: ElevationConfig = ElevationConfig.forSource(source)
    ): ElevationResult? {
        var anyAltitude = false
        var gain = 0.0
        var loss = 0.0
        for (piece in pieces(points, ElevationSeries.of(points, source))) {
            anyAltitude = true
            val times = LongArray(piece.size) { piece[it].first }
            val cleaned = withoutOutliers(times, DoubleArray(piece.size) { piece[it].second }, cfg)
            val smoothed = movingAverage(times, cleaned, cfg.smoothingWindowMs)
            val (pieceGain, pieceLoss) = hysteresis(smoothed, cfg.hysteresisM)
            gain += pieceGain
            loss += pieceLoss
        }
        return if (anyAltitude) ElevationResult(gain.toFloat(), loss.toFloat()) else null
    }

    /**
     * (time, altitude) runs between breaks — a gap, a bridge or a point without altitude, as the
     * elevation chart draws them ([TrackChartBuilder.buildElevationRuns]); never empty.
     * [altitudes] are the points' own, by index.
     */
    private fun pieces(points: List<TrackPoint>, altitudes: List<Double?>): List<List<Pair<Long, Double>>> {
        val pieces = mutableListOf<List<Pair<Long, Double>>>()
        var piece = mutableListOf<Pair<Long, Double>>()
        fun close() {
            if (piece.isNotEmpty()) pieces += piece
            piece = mutableListOf()
        }
        for ((i, point) in points.withIndex()) {
            if (point.afterGap) close()
            val altitude = altitudes[i]
            if (altitude == null) close() else piece += point.timestamp to altitude
        }
        close()
        return pieces
    }

    private fun withoutOutliers(times: LongArray, values: DoubleArray, cfg: ElevationConfig): DoubleArray {
        val result = values.copyOf()
        var lo = 0
        var hi = 0
        for (i in values.indices) {
            while (times[i] - times[lo] > cfg.outlierHalfWindowMs) lo++
            if (hi < i) hi = i
            while (hi + 1 < values.size && times[hi + 1] - times[i] <= cfg.outlierHalfWindowMs) hi++
            val window = values.copyOfRange(lo, hi + 1)
            // The window always holds the point itself: never empty
            val median = window.median() ?: continue
            val mad = DoubleArray(window.size) { abs(window[it] - median) }.median() ?: continue
            if (abs(values[i] - median) > maxOf(cfg.outlierMadFactor * mad, cfg.outlierMinCutoffM)) {
                result[i] = median
            }
        }
        return result
    }

    /** Mean of the values within ±window/2 of each point's time. */
    private fun movingAverage(times: LongArray, values: DoubleArray, windowMs: Long): DoubleArray {
        val half = windowMs / 2
        val result = DoubleArray(values.size)
        var lo = 0
        var hi = -1
        var sum = 0.0
        for (i in values.indices) {
            while (hi + 1 < values.size && times[hi + 1] - times[i] <= half) sum += values[++hi]
            while (times[i] - times[lo] > half) sum -= values[lo++]
            result[i] = sum / (hi - lo + 1)
        }
        return result
    }

    /** (gain, loss) of [values] with a dead band of [threshold] at every turn. */
    private fun hysteresis(values: DoubleArray, threshold: Double): Pair<Double, Double> {
        if (values.isEmpty()) return 0.0 to 0.0
        var gain = 0.0
        var loss = 0.0
        // Before the first turn the direction is unknown: the climb starts at the lowest value
        // so far and the descent at the highest, whichever leaves the band first
        var lowest = values[0]
        var highest = values[0]
        var anchor = values[0]
        var direction = 0 // +1 climbing, -1 descending, 0 not known yet
        for (value in values) {
            if (direction == 0) {
                lowest = minOf(lowest, value)
                highest = maxOf(highest, value)
                when {
                    value - lowest >= threshold -> {
                        gain += value - lowest
                        anchor = value
                        direction = 1
                    }
                    highest - value >= threshold -> {
                        loss += highest - value
                        anchor = value
                        direction = -1
                    }
                }
                continue
            }
            val delta = value - anchor
            when {
                // The climb under way goes on: every metre above the anchor counts
                direction > 0 && delta > 0 || direction < 0 && delta >= threshold -> {
                    gain += delta
                    anchor = value
                    direction = 1
                }
                direction < 0 && delta < 0 || direction > 0 && -delta >= threshold -> {
                    loss -= delta
                    anchor = value
                    direction = -1
                }
            }
        }
        return gain to loss
    }
}
