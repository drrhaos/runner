package com.drrhaos.runner.util

import android.location.Location
import com.drrhaos.runner.data.TrackPoint
import kotlin.math.abs

/**
 * Pure-function calculator for speed and pace values.
 * Centralises the math that was duplicated between [WorkoutTrackingService]
 * and [WorkoutTrackingViewModel].
 *
 * @see com.drrhaos.runner.service.WorkoutTrackingService
 * @see com.drrhaos.runner.ui.tracking.WorkoutTrackingViewModel
 */
object SpeedPaceCalculator {

    // ---- Constants ---------------------------------------------------------

    const val METERS_PER_KM = 1000f
    const val KM_PER_MILE = 1.60934f
    const val MS_PER_MINUTE = 60_000f
    const val MS_PER_HOUR = 3_600_000f
    private const val KPH_TO_MPH_COEF = 0.621371

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
            if (points[i].afterGap) continue
            val speed = derivedSpeedMs(points[i - 1], points[i])
            if (speed > maxSpeed) maxSpeed = speed
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
            // Skip phantom distance across GPS gaps
            if (points[i].afterGap) continue
            total += distanceMeters(points[i - 1], points[i])
        }
        return total
    }

    /**
     * Расстояние между двумя точками в метрах.
     * Uses Haversine via Android Location API.
     */
    fun distanceMeters(point1: TrackPoint, point2: TrackPoint): Float {
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
            if (point.afterGap) continue
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
            if (i > 0 && !points[i].afterGap) {
                cumulativeKm += metersToKm(distanceMeters(points[i - 1], points[i]))
            }
            val altitude = points[i].altitude ?: 0.0
            result.add(
                ElevationPoint(
                    distanceKm = cumulativeKm,
                    altitudeMeters = altitude.toFloat(),
                    trackPointIndex = i
                )
            )
        }
        return result
    }

    /**
     * Сегменты по 1 км (метрика) или 1 миле (имперская).
     *
     * Важно: при пересечении границы сегмента время интерполируется до ровно
     * [segmentSizeKm], а «лишняя» дистанция переносится в следующий сегмент.
     * Иначе темп занижается (например 5.67 вместо 6.07 при overshoot ~1.07 км).
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

        for (i in 1 until points.size) {
            val prev = points[i - 1]
            val point = points[i]
            if (point.afterGap) {
                // Close current partial segment, then restart after the gap
                if (currentSegmentDistanceKm > 0f) {
                    val durationMs = prev.timestamp - segmentStartTime
                    segments.add(
                        createSegmentStats(
                            durationMs = durationMs,
                            distanceKm = currentSegmentDistanceKm,
                            metric = metric,
                            startIndex = segmentStartIndex,
                            endIndex = i - 1
                        )
                    )
                }
                currentSegmentDistanceKm = 0f
                segmentStartTime = point.timestamp
                segmentStartIndex = i
                continue
            }
            val stepKm = metersToKm(distanceMeters(prev, point))
            if (stepKm <= 0f) {
                if (i == points.size - 1 && currentSegmentDistanceKm > 0f) {
                    val durationMs = point.timestamp - segmentStartTime
                    segments.add(
                        createSegmentStats(
                            durationMs = durationMs,
                            distanceKm = currentSegmentDistanceKm,
                            metric = metric,
                            startIndex = segmentStartIndex,
                            endIndex = i
                        )
                    )
                }
                continue
            }

            val stepDurationMs = (point.timestamp - prev.timestamp).coerceAtLeast(0L)
            var consumedKm = 0f

            // Один GPS-шаг может пересечь несколько границ сегмента
            while (currentSegmentDistanceKm + (stepKm - consumedKm) >= segmentSizeKm) {
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

            if (i == points.size - 1 && currentSegmentDistanceKm > 0f) {
                val durationMs = point.timestamp - segmentStartTime
                segments.add(
                    createSegmentStats(
                        durationMs = durationMs,
                        distanceKm = currentSegmentDistanceKm,
                        metric = metric,
                        startIndex = segmentStartIndex,
                        endIndex = i
                    )
                )
            }
        }
        return segments
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
