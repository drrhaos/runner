package com.runner.academy.util

import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.Priority

/**
 * Конфигурация GPS для различных режимов трекинга
 */
object GpsConfig {
    
    // Базовые интервалы — для записи тренировки держим частый поток фиксов
    const val HIGH_ACCURACY_INTERVAL = 1000L // 1 секунда
    const val MEDIUM_ACCURACY_INTERVAL = 5000L
    const val LOW_ACCURACY_INTERVAL = 10000L
    
    // Минимальные интервалы
    const val MIN_UPDATE_INTERVAL = 500L // Fused может отдавать чаще базового интервала
    /** 0 = не резать фиксы по дистанции на стороне Fused; фильтр точек — в GpsLocationProcessor. */
    const val MIN_DISTANCE = 0f
    
    /**
     * Адаптивный интервал (км/ч). Для плавной позиции не уходим выше 1 с на беге.
     */
    fun getAdaptiveInterval(currentSpeed: Float): Long {
        return when {
            currentSpeed > 20f -> 500L
            currentSpeed > 5f -> 1000L
            else -> 1000L // даже на медленном беге / стопе — раз в секунду
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
            // 0 = без батчинга, иначе Play Services копит фиксы и отдаёт редко
            setMaxUpdateDelayMillis(0)
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
            setMaxUpdateDelayMillis(0)
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
