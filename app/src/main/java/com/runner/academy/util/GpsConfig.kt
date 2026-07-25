package com.runner.academy.util

import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.Priority

/**
 * Конфигурация GPS для различных режимов трекинга
 */
object GpsConfig {
    
    // Базовые интервалы
    const val HIGH_ACCURACY_INTERVAL = 2000L // 2 секунды для высокой точности
    const val MEDIUM_ACCURACY_INTERVAL = 5000L // 5 секунд для средней точности
    const val LOW_ACCURACY_INTERVAL = 10000L // 10 секунд для низкой точности
    
    // Минимальные интервалы
    const val MIN_UPDATE_INTERVAL = 1000L // 1 секунда минимум
    const val MIN_DISTANCE = 5f // 5 метров минимальное расстояние
    
    // Адаптивные интервалы в зависимости от скорости
    fun getAdaptiveInterval(currentSpeed: Float): Long {
        return when {
            currentSpeed > 20f -> 1000L // Быстрое движение - 1 сек
            currentSpeed > 10f -> 2000L // Средняя скорость - 2 сек
            currentSpeed > 5f -> 3000L  // Медленное движение - 3 сек
            else -> 5000L // Остановка - 5 сек
        }
    }
    
    /**
     * Создает LocationRequest с оптимальными настройками для трекинга тренировок
     */
    fun createWorkoutLocationRequest(): LocationRequest {
        return LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            HIGH_ACCURACY_INTERVAL
        ).apply {
            setMinUpdateIntervalMillis(MIN_UPDATE_INTERVAL)
            setMaxUpdateDelayMillis(HIGH_ACCURACY_INTERVAL * 2)
            setWaitForAccurateLocation(false)
            setMinUpdateDistanceMeters(MIN_DISTANCE)
        }.build()
    }
    
    /**
     * Создает адаптивный LocationRequest с заданным интервалом обновления
     * @param intervalMs интервал обновления в миллисекундах
     */
    fun createAdaptiveLocationRequest(intervalMs: Long): LocationRequest {
        return LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            intervalMs
        ).apply {
            setMinUpdateIntervalMillis(minOf(MIN_UPDATE_INTERVAL, intervalMs))
            setMaxUpdateDelayMillis(intervalMs * 2)
            setWaitForAccurateLocation(false)
            setMinUpdateDistanceMeters(MIN_DISTANCE)
        }.build()
    }
    
    /**
     * Создает LocationRequest для мониторинга GPS статуса (менее агрессивный)
     */
    fun createStatusLocationRequest(): LocationRequest {
        return LocationRequest.Builder(
            Priority.PRIORITY_BALANCED_POWER_ACCURACY,
            MEDIUM_ACCURACY_INTERVAL
        ).apply {
            setMinUpdateIntervalMillis(MEDIUM_ACCURACY_INTERVAL)
            setMaxUpdateDelayMillis(MEDIUM_ACCURACY_INTERVAL * 3)
            setWaitForAccurateLocation(false)
            setMinUpdateDistanceMeters(10f)
        }.build()
    }
}
