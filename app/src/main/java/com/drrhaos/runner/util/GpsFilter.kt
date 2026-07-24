package com.drrhaos.runner.util

import android.location.Location
import android.util.Log
import org.osmdroid.util.GeoPoint

/**
 * Утилита для фильтрации GPS выбросов и неправильных координат
 */
object GpsFilter {

    /** After this silence between fixes, the next valid point is treated as gap resume (no phantom distance). */
    const val GAP_RESUME_THRESHOLD_MS = 15_000L

    // Максимальное расстояние между точками (в метрах) для фильтрации выбросов
    private const val MAX_DISTANCE_BETWEEN_POINTS = 500.0 // 500 метров

    // Максимальная точность GPS (в метрах) - точки с худшей точностью игнорируются
    private const val MAX_ACCEPTABLE_ACCURACY = 100.0 // 100 метров

    // Минимальная точность GPS (в метрах) - точки с лучшей точностью всегда принимаются
    private const val MIN_ACCEPTABLE_ACCURACY = 20.0 // 20 метров

    // По умолчанию: ~50 км/ч; для других типов тренировки передаётся свой порог
    private const val DEFAULT_MAX_REASONABLE_SPEED_MPS = 14.0 // 14 м/с ≈ 50 км/ч

    // Максимальное изменение высоты между точками (в метрах) для фильтрации выбросов
    private const val MAX_ALTITUDE_CHANGE = 50.0 // 50 метров

    /**
     * True when the new fix should re-anchor the track after a GPS outage
     * (long time gap and/or explicit [forceGapResume] from LOST status).
     */
    fun isGapResume(
        previousLocation: Location?,
        newLocation: Location,
        forceGapResume: Boolean = false
    ): Boolean {
        if (forceGapResume && previousLocation != null) return true
        if (previousLocation == null) return false
        val timeDiff = newLocation.time - previousLocation.time
        return timeDiff >= GAP_RESUME_THRESHOLD_MS
    }
    
    /**
     * Проверяет, является ли GPS координата валидной
     */
    fun isValidGpsLocation(location: Location): Boolean {
        Log.d("GpsFilter", "Validating GPS location: lat=${location.latitude}, lon=${location.longitude}, accuracy=${if (location.hasAccuracy()) location.accuracy else "N/A"}m, speed=${if (location.hasSpeed()) location.speed else "N/A"}m/s")
        
        // Проверяем координаты на валидность (обязательная проверка)
        if (!isValidCoordinate(location.latitude, location.longitude)) {
            Log.w("GpsFilter", "Invalid coordinates: lat=${location.latitude}, lon=${location.longitude}")
            return false
        }
        
        // Проверяем точность GPS (если доступна)
        if (location.hasAccuracy()) {
            if (location.accuracy > MAX_ACCEPTABLE_ACCURACY) {
                Log.w("GpsFilter", "GPS accuracy too poor: ${location.accuracy}m > ${MAX_ACCEPTABLE_ACCURACY}m")
                return false
            }
        }
        
        // Проверяем скорость точки (если доступна и слишком высокая, возможно это выброс)
        if (location.hasSpeed()) {
            if (location.speed > DEFAULT_MAX_REASONABLE_SPEED_MPS) {
                Log.w("GpsFilter", "Speed too high: ${location.speed}m/s > ${DEFAULT_MAX_REASONABLE_SPEED_MPS}m/s")
                return false
            }
        }
        
        Log.d("GpsFilter", "GPS location validation passed")
        return true
    }
    
    /**
     * Проверяет, является ли координата валидной
     */
    private fun isValidCoordinate(latitude: Double, longitude: Double): Boolean {
        return latitude in -90.0..90.0 && 
               longitude in -180.0..180.0 &&
               !latitude.isNaN() && 
               !longitude.isNaN() &&
               latitude.isFinite() && 
               longitude.isFinite()
    }
    
    /**
     * Фильтрует GPS выбросы, сравнивая с предыдущей точкой.
     *
     * When [forceGapResume] is true or the time since [previousLocation] exceeds
     * [GAP_RESUME_THRESHOLD_MS], speed/distance outlier checks against the previous
     * point are skipped so the first fix after a tunnel/indoor gap is accepted as
     * a new anchor (caller must not add segment distance across the gap).
     */
    fun filterGpsOutlier(
        newLocation: Location,
        previousLocation: Location?,
        maxReasonableSpeedMps: Float = DEFAULT_MAX_REASONABLE_SPEED_MPS.toFloat(),
        forceGapResume: Boolean = false
    ): Location? {
        // Если это первая точка, проверяем только базовую валидность
        if (previousLocation == null) {
            return if (isValidGpsLocation(newLocation)) {
                Log.d("GpsFilter", "First valid GPS point accepted")
                newLocation
            } else {
                Log.w("GpsFilter", "First GPS point rejected")
                null
            }
        }

        // Проверяем базовую валидность новой точки
        if (!isValidGpsLocation(newLocation)) {
            Log.w("GpsFilter", "GPS point failed basic validation")
            return null
        }

        // Проверяем, не является ли это "прыжком" назад по времени
        if (newLocation.time < previousLocation.time) {
            Log.w("GpsFilter", "GPS point with earlier timestamp rejected")
            return null
        }

        if (isGapResume(previousLocation, newLocation, forceGapResume)) {
            Log.d(
                "GpsFilter",
                "Gap resume accepted: dt=${newLocation.time - previousLocation.time}ms, force=$forceGapResume"
            )
            return newLocation
        }

        // Вычисляем расстояние между точками
        val distance = newLocation.distanceTo(previousLocation)
        
        // Если расстояние слишком большое, считаем это выбросом (проверяем первым)
        if (distance > MAX_DISTANCE_BETWEEN_POINTS) {
            Log.w("GpsFilter", "GPS outlier detected: distance=${distance}m > ${MAX_DISTANCE_BETWEEN_POINTS}m")
            return null
        }
        
        // Вычисляем время между точками
        val timeDiff = newLocation.time - previousLocation.time
        val timeDiffSeconds = timeDiff / 1000.0
        
        // Проверяем скорость между точками (если время > 0)
        if (timeDiffSeconds > 0) {
            // Вычисляем скорость между точками (м/с)
            val calculatedSpeed = distance / timeDiffSeconds
            
            // Если вычисленная скорость превышает допустимую, считаем это выбросом
            if (calculatedSpeed > maxReasonableSpeedMps) {
                Log.w("GpsFilter", "GPS outlier detected: calculated speed=${calculatedSpeed}m/s (${calculatedSpeed * 3.6}km/h) > ${maxReasonableSpeedMps}m/s, distance=${distance}m, time=${timeDiffSeconds}s")
                return null
            }
        }
        
        // Проверяем резкие изменения высоты (если обе точки имеют данные о высоте)
        if (newLocation.hasAltitude() && previousLocation.hasAltitude()) {
            val altitudeChange = kotlin.math.abs(newLocation.altitude - previousLocation.altitude)
            if (altitudeChange > MAX_ALTITUDE_CHANGE) {
                Log.w("GpsFilter", "GPS outlier detected: altitude change=${altitudeChange}m (from ${previousLocation.altitude}m to ${newLocation.altitude}m)")
                return null
            }
        }
        
        // Если новая точка имеет очень хорошую точность, принимаем её
        if (newLocation.accuracy <= MIN_ACCEPTABLE_ACCURACY) {
            Log.d("GpsFilter", "High accuracy GPS point accepted: ${newLocation.accuracy}m")
            return newLocation
        }
        
        // Для точек со средней точностью проверяем дополнительно
        val expectedMaxDistance = calculateExpectedMaxDistance(previousLocation, timeDiff, maxReasonableSpeedMps)
        
        if (distance > expectedMaxDistance) {
            Log.w("GpsFilter", "GPS point exceeds expected distance: ${distance}m > ${expectedMaxDistance}m")
            return null
        }
        
        Log.d("GpsFilter", "GPS point accepted: distance=${distance}m, accuracy=${newLocation.accuracy}m")
        return newLocation
    }
    
    /**
     * Вычисляет максимально ожидаемое расстояние на основе скорости и времени
     */
    private fun calculateExpectedMaxDistance(
        previousLocation: Location,
        timeDiffMs: Long,
        maxReasonableSpeedMps: Float
    ): Double {
        val timeDiffSeconds = timeDiffMs / 1000.0
        val speed = previousLocation.speed
        
        // Максимальная скорость для бега + 50% запас
        val maxSpeed = minOf(speed * 1.5, maxReasonableSpeedMps.toDouble())
        
        return maxSpeed * timeDiffSeconds
    }
    
    /**
     * Создает валидный GeoPoint или возвращает null
     */
    fun createValidGeoPoint(location: Location): GeoPoint? {
        // ВРЕМЕННО: Всегда создаем GeoPoint для тестирования
        return GeoPoint(location.latitude, location.longitude)
        
        // return if (isValidGpsLocation(location)) {
        //     GeoPoint(location.latitude, location.longitude)
        // } else {
        //     Log.w("GpsFilter", "Cannot create GeoPoint: invalid location")
        //     null
        // }
    }
    
    /**
     * Создает валидный GeoPoint с фильтрацией выбросов
     */
    fun createValidGeoPointWithFiltering(
        location: Location, 
        previousLocation: Location?
    ): GeoPoint? {
        val filteredLocation = filterGpsOutlier(location, previousLocation)
        return filteredLocation?.let { 
            GeoPoint(it.latitude, it.longitude) 
        }
    }
    
    /**
     * Логирует информацию о GPS точке для отладки
     */
    fun logGpsInfo(location: Location, isAccepted: Boolean) {
        Log.d("GpsFilter", 
            "GPS Point: lat=${location.latitude}, lon=${location.longitude}, " +
            "accuracy=${location.accuracy}m, speed=${location.speed}m/s, " +
            "accepted=$isAccepted"
        )
    }
}
