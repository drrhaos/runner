package com.runner.academy.util

import android.location.Location
import android.util.Log
import com.runner.academy.data.TrackData
import com.runner.academy.data.TrackPoint
import org.osmdroid.util.GeoPoint

/**
 * Утилита для очистки данных тренировок от GPS выбросов
 */
object WorkoutDataCleaner {
    
    /**
     * Очищает TrackData от GPS выбросов и неправильных координат
     */
    fun cleanTrackData(trackData: TrackData): TrackData {
        if (trackData.points.isEmpty()) {
            Log.d("WorkoutDataCleaner", "TrackData is empty, nothing to clean")
            return trackData
        }
        
        Log.d("WorkoutDataCleaner", "Cleaning TrackData with ${trackData.points.size} points")
        
        val cleanedPoints = mutableListOf<TrackPoint>()
        var previousLocation: Location? = null
        var removedCount = 0
        
        for (trackPoint in trackData.points) {
            val location = createLocationFromTrackPoint(trackPoint)
            if (location == null) {
                removedCount++
                continue
            }
            val forceGap = GpsFilter.isGapResume(previousLocation, location) || trackPoint.afterGap

            // Применяем фильтрацию GPS (с учётом разрывов сигнала)
            val filteredLocation = GpsFilter.filterGpsOutlier(
                location,
                previousLocation,
                forceGapResume = forceGap
            )

            if (filteredLocation != null) {
                val afterGap = forceGap && previousLocation != null
                val cleanedTrackPoint = TrackPoint(
                    latitude = filteredLocation.latitude,
                    longitude = filteredLocation.longitude,
                    timestamp = filteredLocation.time,
                    accuracy = filteredLocation.accuracy,
                    speed = filteredLocation.speed,
                    altitude = filteredLocation.altitude,
                    afterGap = afterGap,
                    source = trackPoint.source
                )
                cleanedPoints.add(cleanedTrackPoint)
                previousLocation = filteredLocation
            } else {
                removedCount++
                Log.d("WorkoutDataCleaner", "Removed outlier point: lat=${trackPoint.latitude}, lon=${trackPoint.longitude}")
            }
        }
        
        Log.d("WorkoutDataCleaner", "Cleaning completed: removed $removedCount points, kept ${cleanedPoints.size} points")
        
        // Пересчитываем статистику на основе очищенных точек
        return recalculateTrackData(trackData, cleanedPoints)
    }
    
    /**
     * Создает Location из TrackPoint для проверки
     */
    private fun createLocationFromTrackPoint(trackPoint: TrackPoint): Location? {
        if (!GpsFilter.isValidLatLon(trackPoint.latitude, trackPoint.longitude)) {
            return null
        }
        val location = Location("track_cleaner")
        location.latitude = trackPoint.latitude
        location.longitude = trackPoint.longitude
        location.accuracy = trackPoint.accuracy ?: 100f
        location.speed = trackPoint.speed ?: 0f
        location.time = trackPoint.timestamp
        trackPoint.altitude?.let { altitude ->
            location.altitude = altitude
        }
        return location
    }
    
    /**
     * Пересчитывает статистику TrackData на основе очищенных точек
     */
    private fun recalculateTrackData(originalTrackData: TrackData, cleanedPoints: List<TrackPoint>): TrackData {
        if (cleanedPoints.isEmpty()) {
            Log.w("WorkoutDataCleaner", "No valid points after cleaning, returning original data")
            return originalTrackData
        }
        
        // Сортируем по времени и заново выставляем afterGap — иначе сортировка
        // ломает флаги разрывов, проставленные в исходном порядке.
        val sortedPoints = cleanedPoints.sortedBy { it.timestamp }
        val orderedPoints = sortedPoints.mapIndexed { index, point ->
            if (index == 0) {
                point.copy(afterGap = false)
            } else {
                val prev = sortedPoints[index - 1]
                // Recompute gap after sort using geometry/time (ignore stale afterGap from unsorted order)
                val gap = SpeedPaceCalculator.isTrackGapStep(
                    prev.copy(afterGap = false),
                    point.copy(afterGap = false)
                )
                point.copy(afterGap = gap)
            }
        }
        
        // Вычисляем общее расстояние через SpeedPaceCalculator
        val totalDistance = SpeedPaceCalculator.totalDistanceMeters(orderedPoints)
        
        // Вычисляем общую продолжительность
        val startTime = orderedPoints.first().timestamp
        val endTime = orderedPoints.last().timestamp
        val totalDuration = (endTime - startTime).coerceAtLeast(0L)
        
        // Вычисляем среднюю и максимальную скорость из derived calculations (не сырой GPS speed)
        val avgSpeed = SpeedPaceCalculator.averageSpeedMs(totalDistance, totalDuration)
        val maxSpeed = SpeedPaceCalculator.maxDerivedSpeedMs(orderedPoints)
        
        Log.d("WorkoutDataCleaner", "Recalculated stats: distance=${totalDistance}m, duration=${totalDuration}ms, avgSpeed=${avgSpeed}m/s")
        
        return TrackData(
            points = orderedPoints,
            totalDistance = totalDistance,
            totalDuration = totalDuration,
            avgSpeed = avgSpeed,
            maxSpeed = maxSpeed,
            startTime = startTime,
            endTime = endTime
        )
    }
    
    /**
     * Очищает список GeoPoint от неправильных координат
     */
    fun cleanGeoPoints(geoPoints: List<GeoPoint>): List<GeoPoint> {
        return geoPoints.filter { geoPoint ->
            val location = Location("geo_cleaner")
            location.latitude = geoPoint.latitude
            location.longitude = geoPoint.longitude
            location.accuracy = 10f // Предполагаем хорошую точность для отображения
            location.speed = 0f
            location.time = System.currentTimeMillis()
            
            GpsFilter.isValidGpsLocation(location)
        }
    }
    
    /**
     * Проверяет, нужна ли очистка данных тренировки
     */
    fun needsCleaning(trackData: TrackData): Boolean {
        if (trackData.points.isEmpty()) return false
        
        var outlierCount = 0
        var previousLocation: Location? = null
        
        for (trackPoint in trackData.points) {
            val location = createLocationFromTrackPoint(trackPoint)
            if (location == null) {
                outlierCount++
                continue
            }

            if (!GpsFilter.isValidGpsLocation(location)) {
                outlierCount++
            } else if (previousLocation != null && !trackPoint.afterGap) {
                val distance = location.distanceTo(previousLocation)
                if (distance > 500) { // Более 500м между точками без разрыва GPS
                    outlierCount++
                }
            }

            previousLocation = location
        }
        
        val outlierPercentage = (outlierCount.toFloat() / trackData.points.size) * 100
        val needsCleaning = outlierPercentage > 5 // Более 5% выбросов
        
        Log.d("WorkoutDataCleaner", "Outlier analysis: $outlierCount/${trackData.points.size} (${outlierPercentage.toInt()}%) - needs cleaning: $needsCleaning")
        
        return needsCleaning
    }
}
