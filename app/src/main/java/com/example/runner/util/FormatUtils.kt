package com.example.runner.util

import java.text.SimpleDateFormat
import java.util.*

/**
 * Утилиты для форматирования данных
 */
object FormatUtils {
    
    private val dateFormat = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dateTimeFormat = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
    
    /**
     * Форматирует время в миллисекундах в строку HH:MM:SS или MM:SS
     */
    fun formatTime(milliseconds: Long): String {
        val totalSeconds = milliseconds / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60

        return when {
            hours > 0 -> String.format("%d:%02d:%02d", hours, minutes, seconds)
            else -> String.format("%02d:%02d", minutes, seconds)
        }
    }
    
    /**
     * Форматирует скорость в км/ч
     */
    fun formatSpeed(speedKmh: Float): String {
        return String.format("%.1f км/ч", speedKmh)
    }
    
    /**
     * Форматирует темп в минутах на километр
     */
    fun formatPace(paceMinutesPerKm: Float): String {
        if (paceMinutesPerKm <= 0) return "--:-- /км"
        
        val minutes = paceMinutesPerKm.toInt()
        val seconds = ((paceMinutesPerKm - minutes) * 60).toInt()
        
        return String.format("%d:%02d /км", minutes, seconds)
    }
    
    /**
     * Форматирует дистанцию в километрах
     */
    fun formatDistance(distanceKm: Float): String {
        return String.format("%.2f км", distanceKm)
    }
    
    /**
     * Форматирует дату
     */
    fun formatDate(date: Date): String {
        return dateFormat.format(date)
    }
    
    /**
     * Форматирует время
     */
    fun formatTime(date: Date): String {
        return timeFormat.format(date)
    }
    
    /**
     * Форматирует дату и время
     */
    fun formatDateTime(date: Date): String {
        return dateTimeFormat.format(date)
    }
    
    /**
     * Форматирует калории
     */
    fun formatCalories(calories: Int): String {
        return "$calories ккал"
    }
    
    /**
     * Вычисляет среднюю скорость в км/ч
     */
    fun calculateAverageSpeed(distanceKm: Float, durationMs: Long): Float {
        if (durationMs <= 0) return 0f
        val durationHours = durationMs / (1000f * 3600f)
        return if (durationHours > 0) distanceKm / durationHours else 0f
    }
    
    /**
     * Вычисляет темп в минутах на километр
     */
    fun calculatePace(distanceKm: Float, durationMs: Long): Float {
        if (distanceKm <= 0 || durationMs <= 0) return 0f
        val durationMinutes = durationMs / 60000f
        return durationMinutes / distanceKm
    }
    
    /**
     * Вычисляет калории (приблизительно)
     */
    fun calculateCalories(distanceKm: Float, weightKg: Float = 70f): Int {
        return (distanceKm * weightKg).toInt()
    }
}
