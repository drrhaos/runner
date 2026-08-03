package com.runner.academy.util

import android.location.Location
import com.runner.academy.data.SegmentGoalType
import com.runner.academy.data.TrackPoint
import com.runner.academy.data.WorkoutTemplateSegment
import kotlin.math.abs

/**
 * Pure-function calculator for speed and pace values.
 * Centralises the math that was duplicated between [WorkoutTrackingService]
 * and [WorkoutTrackingViewModel].
 *
 * @see com.runner.academy.service.WorkoutTrackingService
 * @see com.runner.academy.ui.tracking.WorkoutTrackingViewModel
 */
object SpeedPaceCalculator {

    // ---- Constants ---------------------------------------------------------

    const val METERS_PER_KM = 1000f
    const val KM_PER_MILE = 1.60934f
    const val MS_PER_MINUTE = 60_000f
    const val MS_PER_HOUR = 3_600_000f
    private const val KPH_TO_MPH_COEF = 0.621371

    /**
     * Distance jump treated as a GPS gap when [TrackPoint.afterGap] was not set
     * (e.g. older tracks or missed gap flags). Prevents one teleported step from
     * exploding into thousands of km/mile chart bars and OOMing the detail screen.
     */
    private const val GAP_DISTANCE_METERS = 500f

    /** Hard cap on km/mile chart bars for pathological tracks. */
    private const val MAX_DISTANCE_SEGMENTS = 500

    // ---- Data classes for chart / segment builders -------------------------

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

    /** Statistics for a 1 km / 1 mile segment. */
    data class SegmentStats(
        val paceMinPerUnit: Float,
        val speedDisplay: Float,
        val distanceKm: Float,
        val durationMs: Long,
        val startIndex: Int,
        val endIndex: Int
    )

    // =======================================================================
    //  Unit conversions
    // =======================================================================

    fun metersToKm(meters: Float): Float = meters / METERS_PER_KM

    fun kmToMiles(km: Float): Float = km / KM_PER_MILE

    fun speedMsToKmh(speedMs: Float): Float = speedMs * 3.6f

    fun kmhToMph(speedKmh: Float): Float = (speedKmh * KPH_TO_MPH_COEF).toFloat()

    // =======================================================================
    //  Pace calculations
    // =======================================================================

    /**
     * Темп в мин/км из скорости м/с. 0 если скорость невалидна.
     */
    fun paceMinPerKmFromSpeedMs(speedMs: Float): Float {
        return paceFromSpeedKmh(speedMsToKmh(speedMs))
    }

    /**
     * Темп в мин/км из скорости км/ч.
     */
    fun paceFromSpeedKmh(speedKmh: Float): Float {
        return if (speedKmh > 0f) 60f / speedKmh else 0f
    }

    /**
     * Темп мин/км → мин/милю.
     */
    fun paceMinPerKmToMinPerMile(paceMinPerKm: Float): Float {
        if (paceMinPerKm <= 0f) return 0f
        return paceMinPerKm * KM_PER_MILE
    }

    /**
     * Темп сегмента: duration / distance в нужных единицах.
     */
    fun segmentPace(durationSeconds: Double, distanceMeters: Double): Float {
        if (distanceMeters <= 0.0 || durationSeconds <= 0.0) return 0f
        val durationMinutes = durationSeconds / 60.0
        val distanceKm = distanceMeters / 1000.0
        return (durationMinutes / distanceKm).toFloat()
    }

    /**
     * Темп сегмента с выбором метрических/имперских единиц.
     */
    fun segmentPaceMetric(durationMs: Long, distanceKm: Float, metric: Boolean): Float {
        if (distanceKm <= 0f || durationMs <= 0L) return 0f
        val durationMinutes = durationMs / MS_PER_MINUTE
        return if (metric) {
            durationMinutes / distanceKm
        } else {
            durationMinutes / kmToMiles(distanceKm)
        }
    }

    /**
     * Общий средний темп: суммарное время / суммарная дистанция (мин/км).
     * Uses totalDuration / totalDistance — NOT avgDuration / avgDistance.
     */
    fun overallAveragePace(totalDistanceMeters: Double, totalDurationSeconds: Double): Float {
        if (totalDistanceMeters <= 0.0 || totalDurationSeconds <= 0.0) return 0f
        val totalDistanceKm = totalDistanceMeters / 1000.0
        val totalDurationMinutes = totalDurationSeconds / 60.0
        return (totalDurationMinutes / totalDistanceKm).toFloat()
    }

    // =======================================================================
    //  Speed calculations
    // =======================================================================

    /**
     * Средняя скорость в км/ч по дистанции и времени.
     */
    fun averageSpeedKmh(distanceKm: Float, durationMs: Long): Float {
        if (distanceKm <= 0f || durationMs <= 0L) return 0f
        return distanceKm / (durationMs / MS_PER_HOUR)
    }

    /**
     * Средняя скорость по дистанции и времени (м/с).
     */
    fun averageSpeedMs(totalDistanceMeters: Float, totalDurationMs: Long): Float {
        if (totalDistanceMeters <= 0f || totalDurationMs <= 0L) return 0f
        return totalDistanceMeters / (totalDurationMs / 1000f)
    }

    /**
     * Максимальная мгновенная скорость по сегментам трека (м/с), не сырой GPS speed.
     */
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

    /**
     * Мгновенная скорость между двумя точками (м/с).
     * Uses distance between points / time delta — NOT raw GPS speed.
     * При нулевом dt или нулевой дистанции — fallback на GPS speed текущей точки.
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

    /**
     * Скорость сегмента в км/ч или миль/ч.
     */
    fun segmentSpeed(durationSeconds: Double, distanceMeters: Double): Float {
        if (distanceMeters <= 0.0 || durationSeconds <= 0.0) return 0f
        val distanceKm = distanceMeters / 1000.0
        val hours = durationSeconds / 3600.0
        return (distanceKm / hours).toFloat()
    }

    /**
     * Скорость сегмента с выбором метрических/имперских единиц.
     */
    fun segmentSpeedMetric(durationMs: Long, distanceKm: Float, metric: Boolean): Float {
        if (distanceKm <= 0f || durationMs <= 0L) return 0f
        val speedKmh = distanceKm / (durationMs / MS_PER_HOUR)
        return if (metric) speedKmh else kmhToMph(speedKmh)
    }

    // =======================================================================
    //  Distance calculations
    // =======================================================================

    /**
     * Суммарная дистанция трека в метрах.
     * Cumulative distance using point-to-point distances.
     */
    fun totalDistanceMeters(points: List<TrackPoint>): Float {
        if (points.size < 2) return 0f
        var total = 0f
        for (i in 1 until points.size) {
            // Skip phantom distance across GPS gaps / teleports
            if (isTrackGapStep(points[i - 1], points[i])) continue
            total += distanceMeters(points[i - 1], points[i])
        }
        return total
    }

    /**
     * Расстояние между двумя точками в метрах.
     * Uses Haversine via Android Location API.
     */
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

    // =======================================================================
    //  Pace formatting
    // =======================================================================

    /**
     * Компоненты темпа для отображения: целые минуты и секунды (0–59).
     * Ensures seconds stay in 0-59 range (fixes "5:60" bug).
     */
    fun paceToMinutesSeconds(paceMinutes: Float): Pair<Int, Int> {
        if (paceMinutes <= 0f) return 0 to 0
        val totalSeconds = (paceMinutes * 60f).toInt().coerceAtLeast(0)
        return totalSeconds / 60 to (totalSeconds % 60)
    }

    /**
     * Форматирует темп (десятичные минуты) как м:сс, например 6.1167 → "6:07".
     */
    fun formatPaceMmSs(paceMinutes: Float): String {
        if (paceMinutes <= 0f) return "--:--"
        val totalSeconds = (paceMinutes * 60f).toInt().coerceAtLeast(0)
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%d:%02d", minutes, seconds)
    }

    // =======================================================================
    //  Chart series builders
    // =======================================================================

    /**
     * Серия темп/скорость по времени.
     * Скорость — из соседних точек (дистанция/время), не из сырого GPS speed.
     */
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
            if (isTrackGapStep(prev, point)) continue
            val speedMs = derivedSpeedMs(prev, point)
            val speedKmh = speedMsToKmh(speedMs)
            val paceMinPerKm = paceMinPerKmFromSpeedMs(speedMs)

            val paceDisplay = if (metric) paceMinPerKm else paceMinPerKmToMinPerMile(paceMinPerKm)
            val speedDisplay = if (metric) speedKmh else kmhToMph(speedKmh)
            val timeMinutes = if (startTime > 0L) {
                (point.timestamp - startTime) / MS_PER_MINUTE
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

    /**
     * Elevation over cumulative distance.
     */
    fun buildElevationSeries(points: List<TrackPoint>): List<ElevationPoint> {
        if (points.isEmpty() || points.all { it.altitude == null }) return emptyList()

        val result = mutableListOf<ElevationPoint>()
        var cumulativeKm = 0f

        for (i in points.indices) {
            if (i > 0 && !isTrackGapStep(points[i - 1], points[i])) {
                cumulativeKm += metersToKm(distanceMeters(points[i - 1], points[i]))
            }
            val altitude = points[i].altitude?.toFloat() ?: continue
            if (!altitude.isFinite() || !cumulativeKm.isFinite()) continue
            result.add(
                ElevationPoint(
                    distanceKm = cumulativeKm,
                    altitudeMeters = altitude,
                    trackPointIndex = i
                )
            )
        }
        return result
    }

    /**
     * True when the step from [prev] to [point] should not contribute distance.
     *
     * Uses the explicit [TrackPoint.afterGap] flag and large teleports only.
     * Do **not** treat moderate timestamp gaps (e.g. 30s–few minutes) as gaps:
     * sparse GPX / decimated tracks commonly have such spacing, and treating them
     * as gaps emptied km/mile segment charts (and broke [buildSegments] unit tests).
     * Live phantom-distance prevention belongs in [GpsFilter] / the tracking service.
     */
    fun isTrackGapStep(prev: TrackPoint, point: TrackPoint): Boolean {
        if (point.afterGap) return true
        val stepM = distanceMeters(prev, point)
        return stepM >= GAP_DISTANCE_METERS
    }

    /**
     * Сегменты по 1 км (метрика) или 1 миле (имперская).
     *
     * Важно: при пересечении границы сегмента время интерполируется до ровно
     * [segmentSizeKm], а «лишняя» дистанция переносится в следующий сегмент.
     * Иначе темп занижается (например 5.67 вместо 6.07 при overshoot ~1.07 км).
     *
     * GPS gaps / teleports close the current partial bar and restart — they must
     * never be sliced into hundreds of fake km segments.
     */
    fun buildSegments(
        points: List<TrackPoint>,
        metric: Boolean
    ): List<SegmentStats> {
        if (points.size < 2) return emptyList()

        val segmentSizeKm = if (metric) 1.0f else KM_PER_MILE
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
            if (isTrackGapStep(prev, point)) {
                closePartial(i - 1, prev.timestamp)
                currentSegmentDistanceKm = 0f
                segmentStartTime = point.timestamp
                segmentStartIndex = i
                continue
            }
            val stepKm = metersToKm(distanceMeters(prev, point))
            if (stepKm <= 0f) {
                if (i == points.lastIndex) {
                    closePartial(i, point.timestamp)
                }
                continue
            }

            val stepDurationMs = (point.timestamp - prev.timestamp).coerceAtLeast(0L)
            var consumedKm = 0f

            // Один GPS-шаг может пересечь несколько границ сегмента
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
     * Splits the track by template interval goals (duration / distance), matching
     * [IntervalEngine] progression. Gap steps ([isTrackGapStep]) do not add
     * distance; their wall-clock delta is excluded from elapsed (pause-like).
     * After the last plan segment starts, remaining track belongs to that segment.
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
            val distanceKm = metersToKm((distanceM - segmentStartDistanceM).coerceAtLeast(0f))
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
            if (!isTrackGapStep(prev, point)) {
                elapsedMs += stepMs
                distanceM += distanceMeters(prev, point)
            }

            // Advance completed segments except the last (remainder stays on last)
            while (planIndex < planSegments.lastIndex) {
                val segment = planSegments[planIndex]
                val segElapsed = elapsedMs - segmentStartElapsedMs
                val segDist = distanceM - segmentStartDistanceM
                if (progressOf(segment, segElapsed, segDist) < 1f) break
                closeAt(i)
            }
        }

        // Close the in-progress (or final) segment with whatever track remains
        if (planIndex < planSegments.size) {
            closeAt(points.lastIndex)
        }

        return result
    }

    private fun createSegmentStats(
        durationMs: Long,
        distanceKm: Float,
        metric: Boolean,
        startIndex: Int,
        endIndex: Int
    ): SegmentStats {
        return SegmentStats(
            paceMinPerUnit = segmentPaceMetric(durationMs, distanceKm, metric),
            speedDisplay = segmentSpeedMetric(durationMs, distanceKm, metric),
            distanceKm = distanceKm,
            durationMs = durationMs,
            startIndex = startIndex,
            endIndex = endIndex
        )
    }

    // =======================================================================
    //  Index lookups
    // =======================================================================

    /**
     * Индекс точки трека, ближайшей к выбранному времени (минуты от старта).
     */
    fun findNearestPointIndexByTime(points: List<TrackPoint>, timeMinutes: Float): Int {
        if (points.isEmpty()) return -1
        val startTime = points.first().timestamp
        val targetTime = startTime + (timeMinutes * MS_PER_MINUTE).toLong()
        return points.indices.minByOrNull { abs(points[it].timestamp - targetTime) } ?: 0
    }

    /**
     * Индекс точки трека по накопленной дистанции (км).
     */
    fun findPointIndexByDistance(points: List<TrackPoint>, distanceKm: Float): Int {
        if (points.isEmpty()) return -1
        var cumulativeKm = 0f
        for (i in points.indices) {
            if (i > 0) {
                cumulativeKm += metersToKm(distanceMeters(points[i - 1], points[i]))
            }
            if (cumulativeKm >= distanceKm) return i
        }
        return points.lastIndex
    }

    // =======================================================================
    //  Legacy methods — kept for backward compatibility
    // =======================================================================

    /**
     * Compute instantaneous speed in km/h from a single segment.
     *
     * @param distanceDiff Distance of the segment in kilometres.
     * @param timeDiff Milliseconds elapsed for this segment.
     * @return Speed in km/h, or `0f` when inputs are invalid.
     */
    fun computeCurrentSpeed(distanceDiff: Double, timeDiff: Long): Float {
        if (distanceDiff <= 0.0 || timeDiff <= 0L) return 0f
        val hours = timeDiff / (1000f * 3600f)
        if (hours <= 0f) return 0f
        return (distanceDiff / hours).toFloat()
    }

    /**
     * Compute average speed in km/h over the full workout.
     *
     * @param totalDistance Total distance covered in kilometres.
     * @param totalTimeMillis Total active time in milliseconds.
     * @return Average speed in km/h, or `0f` when inputs are invalid.
     */
    fun computeAverageSpeedKmH(totalDistance: Double, totalTimeMillis: Long): Float {
        if (totalDistance <= 0.0 || totalTimeMillis <= 0L) return 0f
        val hours = totalTimeMillis / (1000f * 3600f)
        if (hours <= 0f) return 0f
        return (totalDistance / hours).toFloat()
    }

    /**
     * Compute raw pace in minutes per kilometer from a speed value.
     * Used internally for storage in the session data model.
     *
     * @param speedKmH Speed in km/h.
     * @return Pace in min/km, or `0f` when speed is zero.
     */
    fun computePaceRaw(speedKmH: Float): Float {
        if (speedKmH <= 0f) return 0f
        return 60f / speedKmH
    }

    /**
     * Format pace as "min:sec" per kilometer from a speed value.
     *
     * @param speedKmH Speed in km/h.
     * @return Formatted string like `"5:30"` or `"0:00"` when speed is zero.
     */
    fun computePaceMinPerKm(speedKmH: Float): String {
        if (speedKmH <= 0f) return "0:00"
        val paceMinutes = 60f / speedKmH
        val minutes = paceMinutes.toInt()
        val seconds = ((paceMinutes - minutes) * 60).toInt().coerceIn(0, 59)
        return "%d:%02d".format(minutes, seconds)
    }
}
