package com.runner.academy.util

import com.runner.academy.data.TrackPoint
import com.runner.academy.data.WorkoutTemplateSegment

/**
 * Compatibility facade for pace/speed/track math.
 *
 * Prefer the focused modules:
 * - [SpeedPaceUnits] — constants and unit conversions
 * - [PaceSpeedMath] — pace/speed formulas and formatting
 * - [TrackGeometry] — distances, gaps, derived speeds
 * - [TrackChartBuilder] — chart series and km/plan segments
 */
object SpeedPaceCalculator {

    const val METERS_PER_KM = SpeedPaceUnits.METERS_PER_KM
    const val KM_PER_MILE = SpeedPaceUnits.KM_PER_MILE
    const val MS_PER_MINUTE = SpeedPaceUnits.MS_PER_MINUTE
    const val MS_PER_HOUR = SpeedPaceUnits.MS_PER_HOUR

    fun metersToKm(meters: Float): Float = SpeedPaceUnits.metersToKm(meters)

    fun kmToMiles(km: Float): Float = SpeedPaceUnits.kmToMiles(km)

    fun speedMsToKmh(speedMs: Float): Float = SpeedPaceUnits.speedMsToKmh(speedMs)

    fun kmhToMph(speedKmh: Float): Float = SpeedPaceUnits.kmhToMph(speedKmh)

    fun paceMinPerKmFromSpeedMs(speedMs: Float): Float =
        PaceSpeedMath.paceMinPerKmFromSpeedMs(speedMs)

    fun paceFromSpeedKmh(speedKmh: Float): Float = PaceSpeedMath.paceFromSpeedKmh(speedKmh)

    fun paceMinPerKmToMinPerMile(paceMinPerKm: Float): Float =
        PaceSpeedMath.paceMinPerKmToMinPerMile(paceMinPerKm)

    fun segmentPace(durationSeconds: Double, distanceMeters: Double): Float =
        PaceSpeedMath.segmentPace(durationSeconds, distanceMeters)

    fun segmentPaceMetric(durationMs: Long, distanceKm: Float, metric: Boolean): Float =
        PaceSpeedMath.segmentPaceMetric(durationMs, distanceKm, metric)

    fun overallAveragePace(totalDistanceMeters: Double, totalDurationSeconds: Double): Float =
        PaceSpeedMath.overallAveragePace(totalDistanceMeters, totalDurationSeconds)

    fun averageSpeedKmh(distanceKm: Float, durationMs: Long): Float =
        PaceSpeedMath.averageSpeedKmh(distanceKm, durationMs)

    fun averageSpeedMs(totalDistanceMeters: Float, totalDurationMs: Long): Float =
        PaceSpeedMath.averageSpeedMs(totalDistanceMeters, totalDurationMs)

    fun maxDerivedSpeedMs(points: List<TrackPoint>): Float =
        TrackGeometry.maxDerivedSpeedMs(points)

    fun derivedSpeedMs(point1: TrackPoint, point2: TrackPoint): Float =
        TrackGeometry.derivedSpeedMs(point1, point2)

    fun segmentSpeed(durationSeconds: Double, distanceMeters: Double): Float =
        PaceSpeedMath.segmentSpeed(durationSeconds, distanceMeters)

    fun segmentSpeedMetric(durationMs: Long, distanceKm: Float, metric: Boolean): Float =
        PaceSpeedMath.segmentSpeedMetric(durationMs, distanceKm, metric)

    fun totalDistanceMeters(points: List<TrackPoint>): Float =
        TrackGeometry.totalDistanceMeters(points)

    fun distanceMeters(point1: TrackPoint, point2: TrackPoint): Float =
        TrackGeometry.distanceMeters(point1, point2)

    fun paceToMinutesSeconds(paceMinutes: Float): Pair<Int, Int> =
        PaceSpeedMath.paceToMinutesSeconds(paceMinutes)

    fun formatPaceMmSs(paceMinutes: Float): String = PaceSpeedMath.formatPaceMmSs(paceMinutes)

    fun buildPaceSpeedSeries(points: List<TrackPoint>, metric: Boolean): List<PaceSpeedPoint> =
        TrackChartBuilder.buildPaceSpeedSeries(points, metric)

    fun buildElevationSeries(points: List<TrackPoint>): List<ElevationPoint> =
        TrackChartBuilder.buildElevationSeries(points)

    fun isTrackGapStep(prev: TrackPoint, point: TrackPoint): Boolean =
        TrackGeometry.isTrackGapStep(prev, point)

    fun buildSegments(points: List<TrackPoint>, metric: Boolean): List<SegmentStats> =
        TrackChartBuilder.buildSegments(points, metric)

    fun buildSegmentsFromPlan(
        points: List<TrackPoint>,
        planSegments: List<WorkoutTemplateSegment>,
        metric: Boolean
    ): List<SegmentStats> = TrackChartBuilder.buildSegmentsFromPlan(points, planSegments, metric)

    fun findNearestPointIndexByTime(points: List<TrackPoint>, timeMinutes: Float): Int =
        TrackChartBuilder.findNearestPointIndexByTime(points, timeMinutes)

    fun findPointIndexByDistance(points: List<TrackPoint>, distanceKm: Float): Int =
        TrackChartBuilder.findPointIndexByDistance(points, distanceKm)

    fun computeCurrentSpeed(distanceDiff: Double, timeDiff: Long): Float =
        PaceSpeedMath.computeCurrentSpeed(distanceDiff, timeDiff)

    fun computeAverageSpeedKmH(totalDistance: Double, totalTimeMillis: Long): Float =
        PaceSpeedMath.computeAverageSpeedKmH(totalDistance, totalTimeMillis)

    fun computePaceRaw(speedKmH: Float): Float = PaceSpeedMath.computePaceRaw(speedKmH)

    fun computePaceMinPerKm(speedKmH: Float): String = PaceSpeedMath.computePaceMinPerKm(speedKmH)
}
