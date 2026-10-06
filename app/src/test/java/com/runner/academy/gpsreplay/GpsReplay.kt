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
    val gapCount: Int,
    /** Live only: step distance counted but not closed by a fix at the end (the run's tail). */
    val openStepMeters: Float = 0f
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
        stepDistance: StepDistanceEstimator? = null,
        /**
         * The step sensor between fixes (steps, cadence at a time), for the service's 1 s timer
         * that counts a silence by steps; null: no ticks (fixes alone).
         */
        stepsAt: ((Long) -> Pair<Int, Float?>?)? = null,
        /** The run stops at this time (ticks run until then); null: at the last fix. */
        stopAtMs: Long? = null,
        /** The run starts at this time (ticks from then on); null: at the first fix. */
        startAtMs: Long? = null
    ): ReplayResult {
        val processor = GpsLocationProcessor()
        processor.reset(workoutType = type, stepDistance = stepDistance)
        var distance = 0.0
        var nextTickMs = (startAtMs ?: raw.firstOrNull()?.timestamp ?: 0L) + TICK_MS
        fun tickUntil(endMs: Long) {
            val sensor = stepsAt ?: return
            while (nextTickMs < endMs) {
                sensor(nextTickMs)?.let { (steps, cadence) ->
                    distance += processor.countSilence(nextTickMs, steps, cadence)
                }
                nextTickMs += TICK_MS
            }
        }
        var gaps = 0
        var trackPoints = mutableListOf<org.osmdroid.util.GeoPoint>()
        var trackData = mutableListOf<TrackPoint>()
        var rawData = mutableListOf<TrackPoint>()

        for (point in raw) {
            tickUntil(point.timestamp)
            val location = TrackSanitizer.toLocation(point)
            val result = processor.processLocation(
                location, trackPoints, trackData, rawData,
                steps = point.steps,
                cadence = point.cadence
            )
            trackPoints = result.trackPoints
            trackData = result.trackDataPoints
            rawData = result.rawTrackDataPoints
            distance += result.distanceDeltaMeters
            if (result is GpsLocationProcessor.ProcessResult.Accepted && result.afterGap) gaps++
        }
        stopAtMs?.let { tickUntil(it + 1) }
        return ReplayResult(distance, trackData, gaps, processor.pendingStepMeters)
    }

    private const val TICK_MS = 1_000L

    fun saved(
        raw: List<TrackPoint>,
        type: WorkoutType = WorkoutType.EASY_RUN,
        stepDistance: StepDistanceEstimator? = null,
        /** The live run's open step distance at Stop ([ReplayResult.openStepMeters]). */
        tailMeters: Float = 0f
    ): ReplayResult {
        val points = TrackSanitizer.sanitize(raw, type, stepDistance, tailMeters)
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
    /** Seconds (from start) with a fix only every [sparseEverySec] seconds — a weak receiver. */
    val sparseSec: IntRange? = null,
    val sparseEverySec: Int = 20,
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
    val seed: Long = 42L,
    /**
     * The run as a sequence of phases along [route]; set, it replaces [speedMps] and the
     * stand-still fields as the timeline. Null: the single-speed run (older scenarios).
     */
    val phases: List<Phase>? = null
) {
    /** One stretch of a phased run. Steps grow only while moving (stride [strideM]). */
    sealed class Phase {
        /** [meters] along the route at [speedMps]. */
        data class Run(val meters: Double, val speedMps: Double = 3.3) : Phase()

        /** [sec] seconds walking at [speedMps]. */
        data class Walk(val sec: Int, val speedMps: Double = 1.2) : Phase()

        /**
         * [sec] seconds standing; fixes keep coming. [stepsContinue]: stepping on the spot at
         * the cadence of the phase before.
         */
        data class Stand(val sec: Int, val stepsContinue: Boolean = false) : Phase()

        /** [sec] seconds on a manual pause: no fixes and no steps, the time goes on. */
        data class ManualPause(val sec: Int) : Phase()
    }

    private class Span(
        val fromSec: Double,
        val toSec: Double,
        val fromM: Double,
        val speedMps: Double,
        val fromSteps: Double,
        val stepsPerSec: Double,
        val phase: Phase
    )

    private val spans: List<Span>? by lazy {
        val list = phases ?: return@lazy null
        var sec = 0.0
        var meters = 0.0
        var steps = 0.0
        var lastStepsPerSec = 0.0
        list.map { phase ->
            val (duration, speed) = when (phase) {
                is Phase.Run -> phase.meters / phase.speedMps to phase.speedMps
                is Phase.Walk -> phase.sec.toDouble() to phase.speedMps
                is Phase.Stand -> phase.sec.toDouble() to 0.0
                is Phase.ManualPause -> phase.sec.toDouble() to 0.0
            }
            val stride = strideM
            val stepsPerSec = when {
                stride == null -> 0.0
                speed > 0.0 -> speed / stride
                phase is Phase.Stand && phase.stepsContinue -> lastStepsPerSec
                else -> 0.0
            }
            if (speed > 0.0) lastStepsPerSec = stepsPerSec
            Span(sec, sec + duration, meters, speed, steps, stepsPerSec, phase).also {
                sec += duration
                meters += speed * duration
                steps += stepsPerSec * duration
            }
        }
    }

    private fun spanAt(t: Double): Span? {
        val list = spans ?: return null
        return list.firstOrNull { t < it.toSec } ?: list.last()
    }

    /** Manual pause phases as [from, to) seconds from the start. */
    val manualPauseSeconds: List<Pair<Int, Int>>
        get() = spans.orEmpty().filter { it.phase is Phase.ManualPause }
            .map { it.fromSec.toInt() to it.toSec.toInt() }

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

    val durationSec: Int
        get() = spans?.last()?.toSec?.toInt() ?: (standStillSec + (routeLengthM / speedMps).toInt())

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
            val onManualPause = spanAt(t)?.phase is Phase.ManualPause
            val skippedBySparse = sparseSec != null && second in sparseSec &&
                (second - sparseSec.first) % sparseEverySec != 0
            if ((gapSec == null || second !in gapSec) && !onManualPause && !skippedBySparse) {
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
                    speed = burst?.speedMps ?: if (moving && noisy) speedAt(t).toFloat() else 0f,
                    altitude = burst?.altitudeM ?: ALTITUDE_M
                ).let { p ->
                    val (steps, cadence) = stepsAndCadence(t) ?: return@let p
                    p.copy(steps = steps, cadence = cadence)
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

    /** The step sensor at [timeMs] (steps since the start, cadence); null without [strideM]. */
    fun stepsAt(timeMs: Long): Pair<Int, Float?>? = stepsAndCadence((timeMs - START_TIME) / 1000.0)

    private fun stepsAndCadence(t: Double): Pair<Int, Float>? {
        val stride = strideM ?: return null
        val span = spanAt(t) ?: run {
            val cadence = if (isStanding(t)) 0f else (60.0 * speedMps / stride).toFloat()
            return (movedAt(t) / stride).toInt() to cadence
        }
        val local = (t - span.fromSec).coerceIn(0.0, span.toSec - span.fromSec)
        return (span.fromSteps + span.stepsPerSec * local).toInt() to (60.0 * span.stepsPerSec).toFloat()
    }

    private fun speedAt(t: Double): Double = spanAt(t)?.speedMps ?: speedMps

    /** Time of the run's start. */
    val startTimeMs: Long get() = START_TIME

    /** Time of the run's end, the last second of [durationSec]. */
    val endTimeMs: Long get() = START_TIME + durationSec * 1000L

    private fun movedAt(t: Number): Double {
        spanAt(t.toDouble())?.let { span ->
            val local = (t.toDouble() - span.fromSec).coerceIn(0.0, span.toSec - span.fromSec)
            return (span.fromM + span.speedMps * local).coerceAtMost(routeLengthM)
        }
        val unstopped = t.toDouble() * speedMps
        val moved = if (unstopped > standStillAtM) {
            (unstopped - standStillSec * speedMps).coerceAtLeast(standStillAtM)
        } else {
            unstopped
        }
        return moved.coerceAtMost(routeLengthM)
    }

    private fun isStanding(t: Double): Boolean {
        spanAt(t)?.let { return it.speedMps <= 0.0 }
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
