package com.runner.academy.gpsreplay

import android.location.Location
import com.runner.academy.data.TrackPoint
import com.runner.academy.data.WorkoutType
import com.runner.academy.service.GpsLocationProcessor
import com.runner.academy.util.TrackGeometry
import com.runner.academy.util.TrackSanitizer
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** Outcome of running raw fixes through one pipeline. */
data class ReplayResult(
    val distanceMeters: Double,
    val points: List<TrackPoint>,
    val gapCount: Int
)

/**
 * Replays raw fixes through both production pipelines:
 *  - [live]: what the tracking service shows during the run ([GpsLocationProcessor]);
 *  - [saved]: what is stored in the database ([TrackSanitizer] + [TrackGeometry]).
 */
object GpsReplay {

    /**
     * Mirrors the per-fix loop of `WorkoutTrackingService.updateLocation`: the previous
     * accepted fix is the reference, rejected fixes do not move it.
     * Keep in sync with the service when that loop changes.
     */
    fun live(raw: List<TrackPoint>, type: WorkoutType = WorkoutType.EASY_RUN): ReplayResult {
        val processor = GpsLocationProcessor()
        var last: Location? = null
        var distance = 0.0
        var gaps = 0
        var trackPoints = mutableListOf<org.osmdroid.util.GeoPoint>()
        var trackData = mutableListOf<TrackPoint>()
        var rawData = mutableListOf<TrackPoint>()

        for (point in raw) {
            val result = processor.processLocation(
                TrackSanitizer.toLocation(point),
                last,
                type,
                trackPoints,
                trackData,
                rawData
            ) ?: continue
            trackPoints = result.trackPoints
            trackData = result.trackDataPoints
            rawData = result.rawTrackDataPoints
            if (result is GpsLocationProcessor.ProcessResult.Accepted) {
                distance += result.segmentDistanceMeters
                if (result.afterGap) gaps++
                last = result.filteredLocation
            }
        }
        return ReplayResult(distance, trackData, gaps)
    }

    fun saved(raw: List<TrackPoint>, type: WorkoutType = WorkoutType.EASY_RUN): ReplayResult {
        val points = TrackSanitizer.sanitize(raw, type)
        return ReplayResult(
            distanceMeters = TrackGeometry.totalDistanceMeters(points).toDouble(),
            points = points,
            gapCount = points.count { it.afterGap }
        )
    }
}

/**
 * Deterministic synthetic run along a polyline (metres east/north of an origin).
 *
 * GPS error is an AR(1) process: real receivers drift smoothly rather than jumping
 * independently every second, so white noise would grossly inflate distance.
 */
data class SyntheticRun(
    val route: List<Pair<Double, Double>>,
    val speedMps: Double = 3.3,
    val intervalMs: Long = 1_000L,
    val standStillSec: Int = 0,
    val noiseInnovationM: Double = 0.4,
    val noiseDecay: Double = 0.95,
    val accuracyM: Float = 6f,
    /** Seconds (from start) with no fixes at all — tunnel / indoor. */
    val gapSec: IntRange? = null,
    /** Seconds at which a single fix jumps [outlierOffsetM] off the route. */
    val outlierAtSec: Set<Int> = emptySet(),
    val outlierOffsetM: Double = 300.0,
    /** False-signal episode (spoofing / jamming), see [Spoof]. */
    val spoof: Spoof? = null,
    val seed: Long = 42L
) {
    /**
     * A false-signal episode over [seconds] (from start). Fixes keep coming with good reported
     * accuracy, but their position is wrong; the true run continues underneath.
     */
    sealed class Spoof(val seconds: IntRange, val noisy: Boolean) {
        /**
         * Position jumps [eastM]/[northM] away (by default ~17 km, like a spoofer's airport
         * point) and stays there with exact coordinates.
         */
        class Teleport(seconds: IntRange, val eastM: Double = 15_000.0, val northM: Double = 8_000.0) :
            Spoof(seconds, noisy = false)

        /** Receiver repeats, exactly, the position it had when the episode began. */
        class Frozen(seconds: IntRange) : Spoof(seconds, noisy = false)

        /** Position drifts away at [rateMps] towards [headingDeg] (0 = north), then snaps back. */
        class Drift(seconds: IntRange, val rateMps: Double, val headingDeg: Double = 90.0) :
            Spoof(seconds, noisy = true)
    }

    val routeLengthM: Double = route.zipWithNext { a, b -> hypot(b.first - a.first, b.second - a.second) }.sum()

    val durationSec: Int get() = standStillSec + (routeLengthM / speedMps).toInt()

    /**
     * Expected distance when the spoofed stretch is dropped and, without a step sensor,
     * bridged by the straight line between the last good fix before it and the first good fix
     * after it. An episode at the very start is simply not counted: the track begins with the
     * first good fix. Assumes 1 Hz fixes (the bracketing seconds are fix times).
     */
    val expectedDistanceWithoutStepsM: Double
        get() {
            val range = spoof?.seconds ?: return routeLengthM
            val after = movedAt(range.last + 1)
            if (range.first <= 0) return routeLengthM - after
            val before = movedAt(range.first - 1)
            val (x1, y1) = positionAt(before)
            val (x2, y2) = positionAt(after)
            return routeLengthM - (after - before) + hypot(x2 - x1, y2 - y1)
        }

    /** Metres actually covered while no fixes arrive (not counted: the track restarts after a gap). */
    val gapDistanceM: Double
        get() = gapSec?.let { range ->
            val from = movedAt(range.first - 1)
            val to = movedAt(range.last + 1)
            to - from
        } ?: 0.0

    fun rawPoints(): List<TrackPoint> {
        val random = Random(seed)
        var errX = 0.0
        var errY = 0.0
        val points = mutableListOf<TrackPoint>()
        val stepSec = intervalMs / 1000.0
        var t = 0.0
        while (t <= durationSec) {
            errX = noiseDecay * errX + gaussian(random) * noiseInnovationM
            errY = noiseDecay * errY + gaussian(random) * noiseInnovationM
            val second = t.toInt()
            if (gapSec == null || second !in gapSec) {
                val spoofed = spoofedPosition(t)
                val (x, y) = spoofed ?: positionAt(movedAt(t))
                val noisy = spoofed == null || spoof?.noisy == true
                val outlier = if (second in outlierAtSec && t - second < stepSec) outlierOffsetM else 0.0
                val moving = t >= standStillSec
                points += point(
                    x + (if (noisy) errX else 0.0) + outlier,
                    y + (if (noisy) errY else 0.0),
                    timeMs = START_TIME + (t * 1000).toLong(),
                    speed = if (moving && noisy) speedMps.toFloat() else 0f
                )
            }
            t += stepSec
        }
        return points
    }

    private fun spoofedPosition(t: Double): Pair<Double, Double>? {
        val episode = spoof ?: return null
        if (t.toInt() !in episode.seconds) return null
        val begin = episode.seconds.first.toDouble()
        return when (episode) {
            is Spoof.Teleport -> episode.eastM to episode.northM
            is Spoof.Frozen -> positionAt(movedAt(begin))
            is Spoof.Drift -> {
                val (x, y) = positionAt(movedAt(t))
                val pulled = episode.rateMps * (t - begin)
                val heading = Math.toRadians(episode.headingDeg)
                x + pulled * sin(heading) to y + pulled * cos(heading)
            }
        }
    }

    /** Distance from a track point to the true route, in metres. */
    fun offRouteMeters(p: TrackPoint): Double {
        val (x, y) = toXY(p)
        return route.zipWithNext().minOf { (a, b) -> distanceToSegment(x, y, a, b) }
    }

    private fun movedAt(t: Number): Double =
        ((t.toDouble() - standStillSec).coerceAtLeast(0.0) * speedMps).coerceAtMost(routeLengthM)

    private fun positionAt(distance: Double): Pair<Double, Double> {
        var left = distance
        for ((a, b) in route.zipWithNext()) {
            val len = hypot(b.first - a.first, b.second - a.second)
            if (left <= len) {
                val f = if (len > 0) left / len else 0.0
                return a.first + f * (b.first - a.first) to a.second + f * (b.second - a.second)
            }
            left -= len
        }
        return route.last()
    }

    private fun point(x: Double, y: Double, timeMs: Long, speed: Float) = TrackPoint(
        latitude = LAT0 + y / M_PER_DEG,
        longitude = LON0 + x / (M_PER_DEG * cos(Math.toRadians(LAT0))),
        timestamp = timeMs,
        accuracy = accuracyM,
        speed = speed,
        altitude = 150.0
    )

    private fun toXY(p: TrackPoint): Pair<Double, Double> =
        (p.longitude - LON0) * M_PER_DEG * cos(Math.toRadians(LAT0)) to (p.latitude - LAT0) * M_PER_DEG

    private fun distanceToSegment(px: Double, py: Double, a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
        val dx = b.first - a.first
        val dy = b.second - a.second
        val len2 = dx * dx + dy * dy
        val t = if (len2 > 0) (((px - a.first) * dx + (py - a.second) * dy) / len2).coerceIn(0.0, 1.0) else 0.0
        return hypot(px - (a.first + t * dx), py - (a.second + t * dy))
    }

    private fun gaussian(random: Random): Double {
        // Box–Muller
        val u1 = random.nextDouble().coerceAtLeast(1e-12)
        val u2 = random.nextDouble()
        return sqrt(-2.0 * kotlin.math.ln(u1)) * cos(2 * Math.PI * u2)
    }

    companion object {
        const val LAT0 = 55.75
        const val LON0 = 37.6
        const val M_PER_DEG = 111_320.0
        const val START_TIME = 1_700_000_000_000L

        /** 400 × 250 m rectangle, [laps] times round — sharp corners like a city block. */
        fun blockLoop(laps: Int = 2): List<Pair<Double, Double>> {
            val lap = listOf(0.0 to 0.0, 400.0 to 0.0, 400.0 to 250.0, 0.0 to 250.0)
            return List(laps) { lap }.flatten() + (0.0 to 0.0)
        }
    }
}
