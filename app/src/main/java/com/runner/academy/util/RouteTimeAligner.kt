package com.runner.academy.util

import com.runner.academy.data.TrackData
import com.runner.academy.data.TrackPoint
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Проставляет время точкам маршрута при замене маршрута тренировки.
 *
 * Если у тренировки есть записанный трек, время берётся из него: каждая точка нового
 * маршрута проецируется на записанный трек, и при близости (≤ [MATCH_RADIUS_METERS])
 * получает время проекции. Поиск идёт только вперёд по ходу записи, поэтому петли и
 * маршруты «туда-обратно» не путаются. Точки без совпадения получают время интерполяцией
 * по дистанции между соседними совпавшими точками (края — по средней скорости).
 *
 * Если записанного трека нет или совпадений мало, время распределяется по маршруту
 * равномерно по дистанции ([distributeByDistance]).
 */
object RouteTimeAligner {

    /** Макс. расстояние от точки маршрута до записанного трека, чтобы взять оттуда время. */
    const val MATCH_RADIUS_METERS = 40.0

    /** Мин. доля дистанции маршрута, покрытая совпадениями, иначе перенос считается неудачным. */
    const val MIN_MATCHED_FRACTION = 0.5

    /** Базовая глубина поиска вперёд по записанному треку от последнего совпадения. */
    private const val BASE_SEARCH_WINDOW_METERS = 500.0

    /**
     * Штраф (м на м) за продвижение вперёд по записанному треку: при одинаковой близости
     * выбирается более ранний проход (важно для «туда-обратно» по одной дороге).
     */
    private const val AHEAD_PENALTY_PER_METER = 0.05

    private const val METERS_PER_DEGREE = 111_320.0

    /**
     * Переносит время с [recorded] на [route]. Возвращает null, если сопоставить
     * маршрут с записью не удалось (меньше 2 совпадений или покрытие < [MIN_MATCHED_FRACTION]).
     */
    fun alignToRecorded(route: TrackData, recorded: TrackData): TrackData? {
        val routePoints = route.points.filter { GpsFilter.isValidLatLon(it.latitude, it.longitude) }
        val recPoints = recorded.points
            .filter { GpsFilter.isValidLatLon(it.latitude, it.longitude) }
            .sortedBy { it.timestamp }
        if (routePoints.size < 2 || recPoints.size < 2) return null

        val projection = LocalProjection(recPoints.first().latitude, recPoints.first().longitude)
        val rec = RecordedPolyline(recPoints, projection)
        val routeCum = cumulativeDistances(routePoints)

        val anchorTimes = LongArray(routePoints.size) { NO_TIME }
        var cursorSegment = -1
        var cursorProgress = 0.0
        var lastAnchorTime = Long.MIN_VALUE
        var routeDistSinceAnchor = 0.0

        for (i in routePoints.indices) {
            if (i > 0) routeDistSinceAnchor += routeCum[i] - routeCum[i - 1]
            val (px, py) = projection.toXY(routePoints[i])
            val hasCursor = cursorSegment >= 0
            val window = BASE_SEARCH_WINDOW_METERS + 2.0 * routeDistSinceAnchor

            var bestScore = Double.MAX_VALUE
            var bestSegment = -1
            var bestProgress = 0.0
            var bestTime = 0L

            var j = if (hasCursor) cursorSegment else 0
            while (j < rec.segmentCount) {
                if (hasCursor && rec.cum[j] - cursorProgress > window) break
                val match = rec.projectOnSegment(j, px, py)
                if (match != null && match.distance <= MATCH_RADIUS_METERS && match.time >= lastAnchorTime) {
                    val ahead = (match.progress - cursorProgress).coerceAtLeast(0.0)
                    val score = match.distance + AHEAD_PENALTY_PER_METER * ahead
                    if (score < bestScore) {
                        bestScore = score
                        bestSegment = j
                        bestProgress = match.progress
                        bestTime = match.time
                    }
                }
                j++
            }

            if (bestSegment >= 0) {
                anchorTimes[i] = bestTime
                cursorSegment = bestSegment
                cursorProgress = bestProgress
                lastAnchorTime = bestTime
                routeDistSinceAnchor = 0.0
            }
        }

        val anchors = anchorTimes.indices.filter { anchorTimes[it] != NO_TIME }
        if (anchors.size < 2) return null
        val first = anchors.first()
        val last = anchors.last()
        val matchedDistance = routeCum[last] - routeCum[first]
        val matchedDurationMs = anchorTimes[last] - anchorTimes[first]
        val routeDistance = routeCum.last()
        if (matchedDistance <= 0.0 || matchedDurationMs <= 0L) return null
        if (matchedDistance / routeDistance < MIN_MATCHED_FRACTION) return null

        val times = LongArray(routePoints.size)
        val avgSpeedMps = matchedDistance / (matchedDurationMs / 1000.0)
        for (k in 0 until first) {
            times[k] = anchorTimes[first] - ((routeCum[first] - routeCum[k]) / avgSpeedMps * 1000.0).toLong()
        }
        for (a in 0 until anchors.size - 1) {
            val from = anchors[a]
            val to = anchors[a + 1]
            times[from] = anchorTimes[from]
            fillBetween(times, routeCum, from, to, anchorTimes[from], anchorTimes[to])
        }
        times[last] = anchorTimes[last]
        for (k in last + 1 until routePoints.size) {
            times[k] = anchorTimes[last] + ((routeCum[k] - routeCum[last]) / avgSpeedMps * 1000.0).toLong()
        }

        // The recorded pauses do not belong to the route's new times
        return buildTrackData(withTimes(routePoints, times), from = route).copy(pauses = null)
    }

    /**
     * Равномерно распределяет [durationMs] по маршруту пропорционально дистанции,
     * начиная с [startTime]. Для ручных тренировок без записанного трека.
     */
    fun distributeByDistance(route: TrackData, startTime: Long, durationMs: Long): TrackData? {
        val routePoints = route.points.filter { GpsFilter.isValidLatLon(it.latitude, it.longitude) }
        if (routePoints.isEmpty()) return null
        val routeCum = cumulativeDistances(routePoints)
        val total = routeCum.last()
        val lastIndex = (routePoints.size - 1).coerceAtLeast(1)
        val times = LongArray(routePoints.size) { k ->
            val fraction = if (total > 0.0) routeCum[k] / total else k.toDouble() / lastIndex
            startTime + (fraction * durationMs).toLong()
        }
        return buildTrackData(withTimes(routePoints, times), from = route).copy(pauses = null)
    }

    /** Сдвигает все временные метки трека на [deltaMs] (смена даты тренировки), вместе с паузами. */
    fun shiftTime(track: TrackData, deltaMs: Long): TrackData {
        if (deltaMs == 0L || track.points.isEmpty()) return track
        return buildTrackData(track.points.map { it.copy(timestamp = it.timestamp + deltaMs) }, from = track)
            .copy(pauses = track.pauses?.map { it.copy(start = it.start + deltaMs, end = it.end + deltaMs) })
    }

    /**
     * Пересчитывает итоговые показатели трека по точкам. Остальные поля трека (паузы,
     * признак синтетического времени, источник высоты) берутся из [from].
     */
    fun buildTrackData(points: List<TrackPoint>, from: TrackData? = null): TrackData {
        val totalDistance = TrackGeometry.totalDistanceMeters(points)
        val startTime = points.firstOrNull()?.timestamp ?: 0L
        val endTime = points.lastOrNull()?.timestamp ?: startTime
        val totalDuration = (endTime - startTime).coerceAtLeast(0L)
        return (from ?: TrackData(points, 0f, 0L, 0f, 0f, 0L, null)).copy(
            points = points,
            totalDistance = totalDistance,
            totalDuration = totalDuration,
            avgSpeed = SpeedPaceCalculator.averageSpeedMs(totalDistance, totalDuration),
            maxSpeed = TrackGeometry.maxDerivedSpeedMs(points),
            startTime = startTime,
            endTime = endTime
        )
    }

    private fun fillBetween(
        times: LongArray,
        routeCum: DoubleArray,
        from: Int,
        to: Int,
        fromTime: Long,
        toTime: Long
    ) {
        val span = routeCum[to] - routeCum[from]
        for (k in from + 1 until to) {
            val fraction = if (span > 0.0) {
                (routeCum[k] - routeCum[from]) / span
            } else {
                (k - from).toDouble() / (to - from)
            }
            times[k] = fromTime + (fraction * (toTime - fromTime)).toLong()
        }
    }

    /** Новые метки времени; скорость пересчитывается из дистанции/времени, старая GPS-скорость неверна. */
    private fun withTimes(points: List<TrackPoint>, times: LongArray): List<TrackPoint> {
        val timed = points.mapIndexed { k, p -> p.copy(timestamp = times[k], speed = null) }
        return timed.mapIndexed { k, p ->
            val neighbour = if (k > 0) k - 1 else 1
            if (neighbour >= timed.size) return@mapIndexed p
            val (a, b) = if (neighbour < k) timed[neighbour] to p else p to timed[neighbour]
            if (TrackGeometry.isTrackGapStep(a, b)) p else p.copy(speed = TrackGeometry.derivedSpeedMs(a, b))
        }
    }

    /** Накопленная дистанция по маршруту (шаги через разрыв GPS не считаются, мосты — считаются). */
    private fun cumulativeDistances(points: List<TrackPoint>): DoubleArray {
        val cum = DoubleArray(points.size)
        for (i in 1 until points.size) {
            cum[i] = cum[i - 1] + TrackGeometry.stepDistanceMeters(points[i - 1], points[i])
        }
        return cum
    }

    private const val NO_TIME = Long.MIN_VALUE

    /** Равнопромежуточная проекция в метрах — достаточно точна в пределах одной тренировки. */
    private class LocalProjection(private val lat0: Double, private val lon0: Double) {
        private val lonScale = METERS_PER_DEGREE * cos(Math.toRadians(lat0))

        fun toXY(p: TrackPoint): Pair<Double, Double> =
            (p.longitude - lon0) * lonScale to (p.latitude - lat0) * METERS_PER_DEGREE
    }

    private class SegmentMatch(val distance: Double, val progress: Double, val time: Long)

    private class RecordedPolyline(points: List<TrackPoint>, projection: LocalProjection) {
        private val xs = DoubleArray(points.size)
        private val ys = DoubleArray(points.size)
        private val times = LongArray(points.size) { points[it].timestamp }
        /** Сегмент j (точки j → j+1) пригоден, если это не разрыв GPS. */
        private val segmentValid = BooleanArray((points.size - 1).coerceAtLeast(0))
        private val segmentLength = DoubleArray(segmentValid.size)
        /** Накопленная длина записанного трека до начала сегмента j. */
        val cum = DoubleArray(segmentValid.size)
        val segmentCount get() = segmentValid.size

        init {
            points.forEachIndexed { i, p ->
                val (x, y) = projection.toXY(p)
                xs[i] = x
                ys[i] = y
            }
            var total = 0.0
            for (j in segmentValid.indices) {
                cum[j] = total
                val dx = xs[j + 1] - xs[j]
                val dy = ys[j + 1] - ys[j]
                segmentLength[j] = sqrt(dx * dx + dy * dy)
                segmentValid[j] = !TrackGeometry.isTrackGapStep(points[j], points[j + 1])
                if (segmentValid[j]) total += segmentLength[j]
            }
        }

        fun projectOnSegment(j: Int, px: Double, py: Double): SegmentMatch? {
            if (!segmentValid[j]) return null
            val len = segmentLength[j]
            val t = if (len > 0.0) {
                (((px - xs[j]) * (xs[j + 1] - xs[j]) + (py - ys[j]) * (ys[j + 1] - ys[j])) / (len * len))
                    .coerceIn(0.0, 1.0)
            } else {
                0.0
            }
            val qx = xs[j] + t * (xs[j + 1] - xs[j])
            val qy = ys[j] + t * (ys[j + 1] - ys[j])
            val dx = px - qx
            val dy = py - qy
            val time = times[j] + (t * (times[j + 1] - times[j])).toLong()
            return SegmentMatch(sqrt(dx * dx + dy * dy), cum[j] + t * len, time)
        }
    }
}
