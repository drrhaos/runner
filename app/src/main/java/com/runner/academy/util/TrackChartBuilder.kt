package com.runner.academy.util

import com.runner.academy.data.PauseInterval
import com.runner.academy.data.SegmentGoalType
import com.runner.academy.data.TrackPoint
import com.runner.academy.data.WorkoutTemplateSegment
import com.runner.academy.data.knownAltitude
import kotlin.math.abs

/** Single data point for pace/speed chart series. */
data class PaceSpeedPoint(
    val timeMinutes: Float,
    val paceMinPerUnit: Float,
    val speedDisplay: Float,
    val trackPointIndex: Int
)

/** Single data point for elevation chart series. */
data class ElevationPoint(
    val distanceKm: Float,
    val altitudeMeters: Float,
    val trackPointIndex: Int
)

/** Single data point for cadence chart series. */
data class CadencePoint(
    val timeMinutes: Float,
    val cadence: Float
)

/** Statistics for a 1 km / 1 mile (or plan) segment. */
data class SegmentStats(
    val paceMinPerUnit: Float,
    val speedDisplay: Float,
    val distanceKm: Float,
    val durationMs: Long,
    val startIndex: Int,
    val endIndex: Int
)

/**
 * Builds chart series and distance/plan segments from track points.
 */
object TrackChartBuilder {

    /** Hard cap on km/mile chart bars for pathological tracks. */
    private const val MAX_DISTANCE_SEGMENTS = 500

    fun buildPaceSpeedSeries(
        points: List<TrackPoint>,
        metric: Boolean
    ): List<PaceSpeedPoint> {
        if (points.size < 2) return emptyList()

        val startTime = points.first().timestamp
        val result = mutableListOf<PaceSpeedPoint>()

        for (i in 1 until points.size) {
            val prev = points[i - 1]
            val point = points[i]
            if (TrackGeometry.isTrackGapStep(prev, point)) continue
            val speedMs = TrackGeometry.derivedSpeedMs(prev, point)
            val speedKmh = SpeedPaceUnits.speedMsToKmh(speedMs)
            val paceMinPerKm = PaceSpeedMath.paceMinPerKmFromSpeedMs(speedMs)

            val paceDisplay = if (metric) {
                paceMinPerKm
            } else {
                PaceSpeedMath.paceMinPerKmToMinPerMile(paceMinPerKm)
            }
            val speedDisplay = if (metric) speedKmh else SpeedPaceUnits.kmhToMph(speedKmh)
            val timeMinutes = if (startTime > 0L) {
                (point.timestamp - startTime) / SpeedPaceUnits.MS_PER_MINUTE
            } else {
                0f
            }
            if (!timeMinutes.isFinite() || !paceDisplay.isFinite() || !speedDisplay.isFinite()) continue

            result.add(
                PaceSpeedPoint(
                    timeMinutes = timeMinutes,
                    paceMinPerUnit = paceDisplay,
                    speedDisplay = speedDisplay,
                    trackPointIndex = i
                )
            )
        }
        return result
    }

    fun buildElevationSeries(points: List<TrackPoint>): List<ElevationPoint> =
        buildElevationRuns(points).flatten()

    /**
     * The elevation line over distance as runs of points: it breaks at a point without altitude
     * ([knownAltitude]: null or the old 0.0, never drawn as zero) and at every
     * [TrackPoint.afterGap] — a gap or a dropped stretch has no altitude, as in [ElevationGain].
     * The distance still counts a bridge, like the total.
     */
    fun buildElevationRuns(points: List<TrackPoint>): List<List<ElevationPoint>> {
        val runs = mutableListOf<List<ElevationPoint>>()
        var run = mutableListOf<ElevationPoint>()
        var cumulativeKm = 0f

        fun closeRun() {
            if (run.isNotEmpty()) runs.add(run)
            run = mutableListOf()
        }

        for (i in points.indices) {
            if (i > 0) {
                // Bridges over a dropped stretch count, plain gaps add nothing
                cumulativeKm += SpeedPaceUnits.metersToKm(
                    TrackGeometry.stepDistanceMeters(points[i - 1], points[i])
                )
                if (points[i].afterGap) closeRun()
            }
            val altitude = points[i].knownAltitude()?.toFloat()
            if (altitude == null || !altitude.isFinite() || !cumulativeKm.isFinite()) {
                closeRun()
                continue
            }
            run.add(
                ElevationPoint(
                    distanceKm = cumulativeKm,
                    altitudeMeters = altitude,
                    trackPointIndex = i
                )
            )
        }
        closeRun()
        return runs
    }

    /**
     * The cadence line as runs of points: a point without cadence (sensor silence) or strictly
     * inside a pause is left out, and the line breaks there, over a pause between two fixes
     * and over a GPS gap (a bridge by steps keeps it). Never zero for a missing value.
     */
    fun buildCadenceSeries(points: List<TrackPoint>, pauses: List<PauseInterval>?): List<List<CadencePoint>> {
        if (points.isEmpty()) return emptyList()
        val startTime = points.first().timestamp
        val runs = mutableListOf<List<CadencePoint>>()
        var run = mutableListOf<CadencePoint>()
        var last: TrackPoint? = null

        fun closeRun() {
            if (run.isNotEmpty()) runs.add(run)
            run = mutableListOf()
        }

        for (point in points) {
            val cadence = point.cadence?.takeIf { it.isFinite() }
            if (cadence == null || TrackPauses.isInside(pauses, point.timestamp)) {
                closeRun()
                last = null
                continue
            }
            val prev = last
            if (prev != null && (
                    TrackPauses.overlapMs(pauses, prev.timestamp, point.timestamp) > 0L ||
                        (TrackGeometry.isTrackGapStep(prev, point) && !TrackGeometry.isBridgeStep(point))
                    )
            ) {
                closeRun()
            }
            run.add(CadencePoint((point.timestamp - startTime) / SpeedPaceUnits.MS_PER_MINUTE, cadence))
            last = point
        }
        closeRun()
        return runs
    }

    /**
     * Segments of 1 km (metric) or 1 mile (imperial).
     *
     * When crossing a boundary, time is interpolated to exactly [segmentSizeKm]
     * and leftover distance carries into the next segment.
     */
    fun buildSegments(
        points: List<TrackPoint>,
        metric: Boolean
    ): List<SegmentStats> {
        if (points.size < 2) return emptyList()

        val segmentSizeKm = if (metric) 1.0f else SpeedPaceUnits.KM_PER_MILE
        val segments = mutableListOf<SegmentStats>()
        var currentSegmentDistanceKm = 0f
        var segmentStartTime = points.first().timestamp
        var segmentStartIndex = 0

        fun closePartial(endIndex: Int, endTime: Long) {
            if (currentSegmentDistanceKm <= 0f) return
            if (segments.size >= MAX_DISTANCE_SEGMENTS) return
            val durationMs = (endTime - segmentStartTime).coerceAtLeast(0L)
            segments.add(
                createSegmentStats(
                    durationMs = durationMs,
                    distanceKm = currentSegmentDistanceKm,
                    metric = metric,
                    startIndex = segmentStartIndex,
                    endIndex = endIndex.coerceIn(0, points.lastIndex)
                )
            )
        }

        for (i in 1 until points.size) {
            if (segments.size >= MAX_DISTANCE_SEGMENTS) break
            val prev = points[i - 1]
            val point = points[i]
            // A bridge over a dropped stretch keeps the split running; a plain gap restarts it
            if (TrackGeometry.isTrackGapStep(prev, point) && !TrackGeometry.isBridgeStep(point)) {
                closePartial(i - 1, prev.timestamp)
                currentSegmentDistanceKm = 0f
                segmentStartTime = point.timestamp
                segmentStartIndex = i
                continue
            }
            val stepKm = SpeedPaceUnits.metersToKm(TrackGeometry.stepDistanceMeters(prev, point))
            if (stepKm <= 0f) {
                if (i == points.lastIndex) {
                    closePartial(i, point.timestamp)
                }
                continue
            }

            val stepDurationMs = (point.timestamp - prev.timestamp).coerceAtLeast(0L)
            var consumedKm = 0f

            while (
                segments.size < MAX_DISTANCE_SEGMENTS &&
                currentSegmentDistanceKm + (stepKm - consumedKm) >= segmentSizeKm
            ) {
                val neededKm = segmentSizeKm - currentSegmentDistanceKm
                val absoluteKmInStep = consumedKm + neededKm
                val fractionOfStep = (absoluteKmInStep / stepKm).coerceIn(0f, 1f)
                val boundaryTime = prev.timestamp + (stepDurationMs * fractionOfStep).toLong()
                val durationMs = (boundaryTime - segmentStartTime).coerceAtLeast(0L)

                segments.add(
                    createSegmentStats(
                        durationMs = durationMs,
                        distanceKm = segmentSizeKm,
                        metric = metric,
                        startIndex = segmentStartIndex,
                        endIndex = i
                    )
                )

                consumedKm = absoluteKmInStep
                currentSegmentDistanceKm = 0f
                segmentStartTime = boundaryTime
                segmentStartIndex = i
            }

            currentSegmentDistanceKm += stepKm - consumedKm

            if (i == points.lastIndex) {
                closePartial(i, point.timestamp)
            }
        }
        return segments
    }

    /**
     * Splits the track by template interval goals, matching [IntervalEngine].
     * Gap steps do not add distance; their wall-clock delta is excluded from elapsed.
     * Bridges over a dropped stretch count both (the runner kept going).
     */
    fun buildSegmentsFromPlan(
        points: List<TrackPoint>,
        planSegments: List<WorkoutTemplateSegment>,
        metric: Boolean
    ): List<SegmentStats> {
        if (points.size < 2 || planSegments.isEmpty()) return emptyList()

        val result = mutableListOf<SegmentStats>()
        var planIndex = 0
        var elapsedMs = 0L
        var distanceM = 0f
        var segmentStartElapsedMs = 0L
        var segmentStartDistanceM = 0f
        var segmentStartIndex = 0

        fun progressOf(segment: WorkoutTemplateSegment, segElapsed: Long, segDist: Float): Float {
            return when (segment.goalType) {
                SegmentGoalType.DURATION -> {
                    val target = segment.durationMs?.takeIf { it > 0 } ?: return 1f
                    (segElapsed.toFloat() / target.toFloat()).coerceIn(0f, 1f)
                }
                SegmentGoalType.DISTANCE -> {
                    val target = segment.distanceMeters?.takeIf { it > 0f } ?: return 1f
                    (segDist / target).coerceIn(0f, 1f)
                }
            }
        }

        fun closeAt(endIndex: Int) {
            if (planIndex >= planSegments.size) return
            val durationMs = (elapsedMs - segmentStartElapsedMs).coerceAtLeast(0L)
            val distanceKm = SpeedPaceUnits.metersToKm(
                (distanceM - segmentStartDistanceM).coerceAtLeast(0f)
            )
            if (durationMs > 0L || distanceKm > 0f) {
                result.add(
                    createSegmentStats(
                        durationMs = durationMs,
                        distanceKm = distanceKm,
                        metric = metric,
                        startIndex = segmentStartIndex,
                        endIndex = endIndex.coerceIn(0, points.lastIndex)
                    )
                )
            }
            planIndex++
            segmentStartElapsedMs = elapsedMs
            segmentStartDistanceM = distanceM
            segmentStartIndex = endIndex.coerceIn(0, points.lastIndex)
        }

        for (i in 1 until points.size) {
            val prev = points[i - 1]
            val point = points[i]
            val stepMs = (point.timestamp - prev.timestamp).coerceAtLeast(0L)
            if (!TrackGeometry.isTrackGapStep(prev, point) || TrackGeometry.isBridgeStep(point)) {
                elapsedMs += stepMs
                distanceM += TrackGeometry.stepDistanceMeters(prev, point)
            }

            while (planIndex < planSegments.lastIndex) {
                val segment = planSegments[planIndex]
                val segElapsed = elapsedMs - segmentStartElapsedMs
                val segDist = distanceM - segmentStartDistanceM
                if (progressOf(segment, segElapsed, segDist) < 1f) break
                closeAt(i)
            }
        }

        if (planIndex < planSegments.size) {
            closeAt(points.lastIndex)
        }

        return result
    }

    fun findNearestPointIndexByTime(points: List<TrackPoint>, timeMinutes: Float): Int {
        if (points.isEmpty()) return -1
        val startTime = points.first().timestamp
        val targetTime = startTime + (timeMinutes * SpeedPaceUnits.MS_PER_MINUTE).toLong()
        return points.indices.minByOrNull { abs(points[it].timestamp - targetTime) } ?: 0
    }

    fun findPointIndexByDistance(points: List<TrackPoint>, distanceKm: Float): Int {
        if (points.isEmpty()) return -1
        var cumulativeKm = 0f
        for (i in points.indices) {
            if (i > 0) {
                cumulativeKm += SpeedPaceUnits.metersToKm(
                    TrackGeometry.distanceMeters(points[i - 1], points[i])
                )
            }
            if (cumulativeKm >= distanceKm) return i
        }
        return points.lastIndex
    }

    private fun createSegmentStats(
        durationMs: Long,
        distanceKm: Float,
        metric: Boolean,
        startIndex: Int,
        endIndex: Int
    ): SegmentStats {
        return SegmentStats(
            paceMinPerUnit = PaceSpeedMath.segmentPaceMetric(durationMs, distanceKm, metric),
            speedDisplay = PaceSpeedMath.segmentSpeedMetric(durationMs, distanceKm, metric),
            distanceKm = distanceKm,
            durationMs = durationMs,
            startIndex = startIndex,
            endIndex = endIndex
        )
    }
}
