package com.runner.academy.util

import android.location.Location
import com.runner.academy.data.TrackPoint
import kotlin.math.abs

/**
 * Расчёты для графиков тренировки: темп, скорость, высота, сегменты.
 * Все дистанции внутри — в метрах/км; конвертация в мили — только на выходе.
 */
object ChartCalculations {

    const val METERS_PER_KM = 1000f
    const val KM_PER_MILE = 1.60934f
    const val MS_PER_MINUTE = 60_000f
    const val MS_PER_HOUR = 3_600_000f

    data class PaceSpeedPoint(
        val timeMinutes: Float,
        val paceMinPerUnit: Float,
        val speedDisplay: Float,
        val trackPointIndex: Int
    )

    data class ElevationPoint(
        val distanceKm: Float,
        val altitudeMeters: Float,
        val trackPointIndex: Int
    )

    data class SegmentStats(
        val paceMinPerUnit: Float,
        val speedDisplay: Float,
        val distanceKm: Float,
        val durationMs: Long,
        val startIndex: Int,
        val endIndex: Int
    )

    fun metersToKm(meters: Float): Float = meters / METERS_PER_KM

    fun kmToMiles(km: Float): Float = km / KM_PER_MILE

    fun speedMsToKmh(speedMs: Float): Float = speedMs * 3.6f

    fun kmhToMph(speedKmh: Float): Float = (speedKmh * KPH_TO_MPH_COEF).toFloat()

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
     * Средняя скорость в км/ч.
     */
    fun averageSpeedKmh(distanceKm: Float, durationMs: Long): Float {
        if (distanceKm <= 0f || durationMs <= 0L) return 0f
        return distanceKm / (durationMs / MS_PER_HOUR)
    }

    /**
     * Темп в мин/милю из дистанции (км) и времени.
     */
    fun paceMinPerMile(distanceKm: Float, durationMs: Long): Float {
        return segmentPace(durationMs, distanceKm, metric = false)
    }

    /**
     * Суммарная дистанция трека в метрах.
     */
    fun totalDistanceMeters(points: List<TrackPoint>): Float {
        if (points.size < 2) return 0f
        var total = 0f
        for (i in 1 until points.size) {
            total += distanceMeters(points[i - 1], points[i])
        }
        return total
    }

    /**
     * Максимальная мгновенная скорость по сегментам трека (м/с), не сырой GPS speed.
     */
    fun maxDerivedSpeedMs(points: List<TrackPoint>): Float {
        if (points.size < 2) return 0f
        var maxSpeed = 0f
        for (i in 1 until points.size) {
            val speed = derivedSpeedMs(points[i - 1], points[i])
            if (speed > maxSpeed) maxSpeed = speed
        }
        return maxSpeed
    }

    /**
     * Компоненты темпа для отображения: целые минуты и секунды (0–59).
     */
    fun paceToMinutesSeconds(paceMinutes: Float): Pair<Int, Int> {
        if (paceMinutes <= 0f) return 0 to 0
        val totalSeconds = (paceMinutes * 60f).toInt().coerceAtLeast(0)
        return totalSeconds / 60 to (totalSeconds % 60)
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
    fun segmentPace(durationMs: Long, distanceKm: Float, metric: Boolean): Float {
        if (distanceKm <= 0f || durationMs <= 0L) return 0f
        val durationMinutes = durationMs / MS_PER_MINUTE
        return if (metric) {
            durationMinutes / distanceKm
        } else {
            durationMinutes / kmToMiles(distanceKm)
        }
    }

    /**
     * Скорость сегмента в км/ч или миль/ч.
     */
    fun segmentSpeed(durationMs: Long, distanceKm: Float, metric: Boolean): Float {
        if (distanceKm <= 0f || durationMs <= 0L) return 0f
        val speedKmh = distanceKm / (durationMs / MS_PER_HOUR)
        return if (metric) speedKmh else kmhToMph(speedKmh)
    }

    /**
     * Средняя скорость по дистанции и времени (м/с).
     */
    fun averageSpeedMs(totalDistanceMeters: Float, totalDurationMs: Long): Float {
        if (totalDistanceMeters <= 0f || totalDurationMs <= 0L) return 0f
        return totalDistanceMeters / (totalDurationMs / 1000f)
    }

    /**
     * Общий средний темп: суммарное время / суммарная дистанция (мин/км).
     */
    fun overallAveragePace(totalDistanceKm: Float, totalDurationMs: Long): Float {
        if (totalDistanceKm <= 0f || totalDurationMs <= 0L) return 0f
        return (totalDurationMs / MS_PER_MINUTE) / totalDistanceKm
    }

    /**
     * Мгновенная скорость между двумя точками (м/с).
     * При нулевом dt или нулевой дистанции — fallback на GPS speed текущей точки.
     */
    fun derivedSpeedMs(prev: TrackPoint, current: TrackPoint): Float {
        val dtSec = (current.timestamp - prev.timestamp) / 1000f
        if (dtSec <= 0f) {
            return current.speed?.takeIf { it > 0f } ?: 0f
        }
        val distanceMeters = distanceMeters(prev, current)
        if (distanceMeters <= 0f) {
            return current.speed?.takeIf { it > 0f } ?: 0f
        }
        return distanceMeters / dtSec
    }

    fun distanceMeters(a: TrackPoint, b: TrackPoint): Float {
        val loc1 = Location("").apply {
            latitude = a.latitude
            longitude = a.longitude
        }
        val loc2 = Location("").apply {
            latitude = b.latitude
            longitude = b.longitude
        }
        return loc1.distanceTo(loc2)
    }

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

    fun buildElevationSeries(points: List<TrackPoint>): List<ElevationPoint> {
        if (points.isEmpty() || points.all { it.altitude == null }) return emptyList()

        val result = mutableListOf<ElevationPoint>()
        var cumulativeKm = 0f

        for (i in points.indices) {
            if (i > 0) {
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
     * Форматирует темп (десятичные минуты) как м:сс, например 6.1167 → "6:07".
     */
    fun formatPaceMmSs(paceMinutes: Float): String {
        if (paceMinutes <= 0f) return "--:--"
        val totalSeconds = (paceMinutes * 60f).toInt().coerceAtLeast(0)
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%d:%02d", minutes, seconds)
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
            paceMinPerUnit = segmentPace(durationMs, distanceKm, metric),
            speedDisplay = segmentSpeed(durationMs, distanceKm, metric),
            distanceKm = distanceKm,
            durationMs = durationMs,
            startIndex = startIndex,
            endIndex = endIndex
        )
    }

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
}
