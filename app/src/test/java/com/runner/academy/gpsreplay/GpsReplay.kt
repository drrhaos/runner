package com.runner.academy.gpsreplay

import com.runner.academy.data.TrackPoint
import com.runner.academy.data.WorkoutType
import com.runner.academy.service.GpsLocationProcessor
import com.runner.academy.util.StepDistanceEstimator
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
     * Mirrors the per-fix loop of `WorkoutTrackingService.updateLocation`: one
     * [GpsLocationProcessor] per run, reset at the start with the run's frozen [stepDistance],
     * fed every fix with the steps and cadence of that moment; the service's session takes the
     * returned lists (on accepted fixes and on a retracted false-signal start) and adds
     * [GpsLocationProcessor.ProcessResult.distanceDeltaMeters] of every fix (segments, the rest
     * of bridges and lead-ins, step distance counted during an episode).
     * Keep in sync with the service when that loop changes.
     */
    fun live(
        raw: List<TrackPoint>,
        type: WorkoutType = WorkoutType.EASY_RUN,
        stepDistance: StepDistanceEstimator? = null
    ): ReplayResult {
        val processor = GpsLocationProcessor()
        processor.reset(stepDistance = stepDistance)
        var distance = 0.0
        var gaps = 0
        var trackPoints = mutableListOf<org.osmdroid.util.GeoPoint>()
        var trackData = mutableListOf<TrackPoint>()
        var rawData = mutableListOf<TrackPoint>()

        for (point in raw) {
            val location = TrackSanitizer.toLocation(point)
            val result = processor.processLocation(
                location, type, trackPoints, trackData, rawData,
                steps = point.steps,
                cadence = point.cadence
            )
            trackPoints = result.trackPoints
            trackData = result.trackDataPoints
            rawData = result.rawTrackDataPoints
            distance += result.distanceDeltaMeters
            if (result is GpsLocationProcessor.ProcessResult.Accepted && result.afterGap) gaps++
        }
        return ReplayResult(distance, trackData, gaps)
    }

    fun saved(
        raw: List<TrackPoint>,
        type: WorkoutType = WorkoutType.EASY_RUN,
        stepDistance: StepDistanceEstimator? = null
    ): ReplayResult {
        val points = TrackSanitizer.sanitize(raw, type, stepDistance)
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
    /** Seconds standing still (fixes keep coming) once [standStillAtM] metres are covered. */
    val standStillSec: Int = 0,
    /** 0 = before the run; mid-route = a traffic light. */
    val standStillAtM: Double = 0.0,
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
    /**
     * True step length in metres; set, every fix carries the steps since the start (the true
     * distance moved / stride) and the cadence. Null: no step sensor.
     */
    val strideM: Double? = null,
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

        /**
         * A few jittering fixes [eastM]/[northM] away with a high reported [speedMps] and a
         * wrong [altitudeM], good accuracy — modelled on a real false signal seen mid-run after
         * two minutes without fixes (Samsung S22).
         */
        class FarBurst(
            seconds: IntRange,
            val eastM: Double = 9_000.0,
            val northM: Double = 9_500.0,
            val speedMps: Float = 28f,
            val altitudeM: Double = 900.0
        ) : Spoof(seconds, noisy = true)
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

    /** Cadence of the run with [strideM], steps/min. */
    val cadenceSpm: Double? get() = strideM?.let { 60.0 * speedMps / it }

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
                val moving = !isStanding(t)
                val burst = (spoof as? Spoof.FarBurst)?.takeIf { spoofed != null }
                points += point(
                    x + (if (noisy) errX else 0.0) + outlier,
                    y + (if (noisy) errY else 0.0),
                    timeMs = START_TIME + (t * 1000).toLong(),
                    speed = burst?.speedMps ?: if (moving && noisy) speedMps.toFloat() else 0f,
                    altitude = burst?.altitudeM ?: ALTITUDE_M
                ).let { p ->
                    val stride = strideM ?: return@let p
                    p.copy(
                        steps = (movedAt(t) / stride).toInt(),
                        cadence = if (moving) (60.0 * speedMps / stride).toFloat() else 0f
                    )
                }
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
            is Spoof.FarBurst -> episode.eastM to episode.northM
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

    private fun movedAt(t: Number): Double {
        val unstopped = t.toDouble() * speedMps
        val moved = if (unstopped > standStillAtM) {
            (unstopped - standStillSec * speedMps).coerceAtLeast(standStillAtM)
        } else {
            unstopped
        }
        return moved.coerceAtMost(routeLengthM)
    }

    private fun isStanding(t: Double): Boolean {
        val from = standStillAtM / speedMps
        return t >= from && t < from + standStillSec
    }

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

    private fun point(x: Double, y: Double, timeMs: Long, speed: Float, altitude: Double) = TrackPoint(
        latitude = LAT0 + y / M_PER_DEG,
        longitude = LON0 + x / (M_PER_DEG * cos(Math.toRadians(LAT0))),
        timestamp = timeMs,
        accuracy = accuracyM,
        speed = speed,
        altitude = altitude
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
        const val ALTITUDE_M = 150.0

        /** 400 × 250 m rectangle, [laps] times round — sharp corners like a city block. */
        fun blockLoop(laps: Int = 2): List<Pair<Double, Double>> {
            val lap = listOf(0.0 to 0.0, 400.0 to 0.0, 400.0 to 250.0, 0.0 to 250.0)
            return List(laps) { lap }.flatten() + (0.0 to 0.0)
        }
    }
}
