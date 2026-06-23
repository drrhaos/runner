package com.drrhaos.runner.util

import android.location.Location
import android.util.Log
import com.drrhaos.runner.data.TrackData
import com.drrhaos.runner.data.TrackPoint
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
            // Создаем Location из TrackPoint для проверки
            val location = createLocationFromTrackPoint(trackPoint)
            
            // Применяем фильтрацию GPS
            val filteredLocation = GpsFilter.filterGpsOutlier(location, previousLocation)
            
            if (filteredLocation != null) {
                // Создаем очищенный TrackPoint
                val cleanedTrackPoint = TrackPoint(
                    latitude = filteredLocation.latitude,
                    longitude = filteredLocation.longitude,
                    timestamp = filteredLocation.time,
                    accuracy = filteredLocation.accuracy,
                    speed = filteredLocation.speed,
                    altitude = filteredLocation.altitude
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
    private fun createLocationFromTrackPoint(trackPoint: TrackPoint): Location {
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
        
        // Сортируем точки по времени
        val sortedPoints = cleanedPoints.sortedBy { it.timestamp }
        
        // Вычисляем общее расстояние
        var totalDistance = 0f
        for (i in 1 until sortedPoints.size) {
            val prevPoint = sortedPoints[i - 1]
            val currentPoint = sortedPoints[i]
            
            val location1 = createLocationFromTrackPoint(prevPoint)
            val location2 = createLocationFromTrackPoint(currentPoint)
            
            val segmentDistance = location1.distanceTo(location2)
            totalDistance += segmentDistance
        }
        
        // Вычисляем общую продолжительность
        val startTime = sortedPoints.first().timestamp
        val endTime = sortedPoints.last().timestamp
        val totalDuration = endTime - startTime
        
        // Вычисляем среднюю и максимальную скорость
        val speeds = sortedPoints.mapNotNull { it.speed }.filter { it > 0 }
        val avgSpeed = if (speeds.isNotEmpty()) speeds.average().toFloat() else 0f
        val maxSpeed = if (speeds.isNotEmpty()) speeds.maxOrNull() ?: 0f else 0f
        
        Log.d("WorkoutDataCleaner", "Recalculated stats: distance=${totalDistance}m, duration=${totalDuration}ms, avgSpeed=${avgSpeed}m/s")
        
        return TrackData(
            points = sortedPoints,
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
            
            if (!GpsFilter.isValidGpsLocation(location)) {
                outlierCount++
            } else if (previousLocation != null) {
                val distance = location.distanceTo(previousLocation)
                if (distance > 500) { // Более 500м между точками
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
