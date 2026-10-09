package com.runner.academy.util

import android.content.Context
import com.runner.academy.R
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.*

const val KPH_TO_MPH_COEF: Double = 0.621371

/**
 * Утилиты для форматирования данных
 */
object FormatUtils {
    private val dateFormat = DateTimeFormatter.ofPattern("dd.MM.yyyy")
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")
    private val dateTimeFormat = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")

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

    /** A record time: "3:58", "24:31", "1:52:07" (no leading zero, whole seconds). */
    fun formatRecordTime(milliseconds: Long): String {
        val totalSeconds = (milliseconds / 1000).coerceAtLeast(0L)
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
        }
    }

    /**
     * How much faster [recordMs] is than [previousMs], as the two times are shown (whole
     * seconds): "25:13" → "24:31" is 0:42 even when the exact difference is 41.3 s.
     */
    fun recordDeltaMs(previousMs: Long, recordMs: Long): Long =
        ((previousMs / 1000) - (recordMs / 1000)).coerceAtLeast(0L) * 1000

    /** [recordDeltaMs] in the format of [formatRecordTime]. */
    fun formatRecordDelta(previousMs: Long, recordMs: Long): String =
        formatRecordTime(recordDeltaMs(previousMs, recordMs))

    fun formatTimeForTTS(milliseconds: Long, context: Context): String {
        val totalSeconds = (milliseconds / 1000).coerceAtLeast(0L).toInt()
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60

        val parts = mutableListOf<String>()
        if (hours > 0) {
            parts += context.resources.getQuantityString(R.plurals.hours, hours, hours)
        }
        if (minutes > 0) {
            parts += context.resources.getQuantityString(R.plurals.minutes, minutes, minutes)
        }
        if (seconds > 0 || parts.isEmpty()) {
            parts += context.resources.getQuantityString(R.plurals.seconds, seconds, seconds)
        }
        return parts.joinToString(" ")
    }
    
    /**
     * Форматирует скорость в км/ч
     */
    fun formatSpeed(speedKmh: Float, kph: Boolean = true, context: Context? = null): String {
        return if (kph) {
            if (context != null) {
                context.getString(R.string.kilometers_per_hour_float, speedKmh)
            } else {
                String.format("%.1f км/ч", speedKmh)
            }
        } else {
            val speedMph = speedKmh * KPH_TO_MPH_COEF
            if (context != null) {
                context.getString(R.string.miles_per_hour_float, speedMph)
            } else {
                String.format("%.1f миль/ч", speedMph)
            }
        }
    }

    /**
     * Форматирует темп в минутах на километр
     */
    fun formatPace(paceMinutesPerKm: Float, context: Context? = null): String {
        if (paceMinutesPerKm <= 0) return context?.getString(R.string.statistics_pace_placeholder) ?: "--:-- /км"
        
        val paceStr = SpeedPaceCalculator.formatPaceMmSs(paceMinutesPerKm)
        return if (context != null) {
            context.getString(R.string.statistics_pace_placeholder).replace("--:--", paceStr)
        } else {
            "$paceStr /км"
        }
    }

    /** "5 minutes 6 seconds per kilometer"; with [metric] false the pace is per mile. */
    fun formatPaceForTTS(paceMinutesPerKm: Float, context: Context, metric: Boolean = true): String {
        if (paceMinutesPerKm <= 0) return ""

        val (minutes, seconds) = SpeedPaceCalculator.paceToMinutesSeconds(paceMinutesPerKm)
        val parts = mutableListOf<String>()
        if (minutes > 0) {
            parts += context.resources.getQuantityString(R.plurals.minutes, minutes, minutes)
        }
        if (seconds > 0 || parts.isEmpty()) {
            parts += context.resources.getQuantityString(R.plurals.seconds, seconds, seconds)
        }
        parts += context.getString(if (metric) R.string.per_km else R.string.per_mile)
        return parts.joinToString(" ")
    }
    
    /**
     * Форматирует дистанцию в километрах
     */
    fun formatDistance(distanceKm: Float, context: Context? = null): String {
        return if (context != null) {
            String.format("%.2f %s", distanceKm, context.getString(R.string.unit_km))
        } else {
            String.format("%.2f км", distanceKm)
        }
    }

    /** Formats a distance given in meters (uses km when ≥ 1000). */
    fun formatDistanceMeters(meters: Float, context: Context): String {
        return if (meters >= 1000f) {
            formatDistance(meters / 1000f, context)
        } else {
            String.format("%.0f %s", meters, context.getString(R.string.unit_m))
        }
    }

    fun formatDistanceMetersForTTS(meters: Float, context: Context): String {
        if (meters >= 1000f) {
            return formatDistanceForTTS(meters / 1000f, context)
        }
        val m = meters.toInt().coerceAtLeast(0)
        return context.resources.getQuantityString(R.plurals.meters, m, m)
    }

    fun formatDistanceForTTS(distanceKm: Float, context: Context, kph: Boolean = true): String {
        val km: Int = distanceKm.toInt()
        val m: Int = ((distanceKm - km)*1000).toInt()

        return if (kph) {
            String.format("%s %s",
                context.resources.getQuantityString(R.plurals.kilometers, km, km),
                context.resources.getQuantityString(R.plurals.meters, m, m)
            )
        } else {
            val miles = (km*KPH_TO_MPH_COEF).toInt()
            String.format("%s %s",
                context.resources.getQuantityString(R.plurals.miles, miles, miles),
                context.resources.getQuantityString(R.plurals.meters, m, m)
            )
        }
    }
    
    /**
     * Форматирует дату
     */
    fun formatDate(date: Date): String {
        val localDate = LocalDateTime.ofInstant(date.toInstant(), java.time.ZoneId.systemDefault())
        return dateFormat.format(localDate)
    }
    
    /**
     * Форматирует время
     */
    fun formatTime(date: Date): String {
        val localDateTime = LocalDateTime.ofInstant(date.toInstant(), java.time.ZoneId.systemDefault())
        return timeFormat.format(localDateTime)
    }
    
    /**
     * Форматирует дату и время
     */
    fun formatDateTime(date: Date): String {
        val localDateTime = LocalDateTime.ofInstant(date.toInstant(), java.time.ZoneId.systemDefault())
        return dateTimeFormat.format(localDateTime)
    }
    
    /**
     * Форматирует калории
     */
    fun formatCalories(calories: Int, context: Context? = null): String {
        return if (context != null) {
            "$calories ${context.getString(R.string.workout_details_calories)}"
        } else {
            "$calories ккал"
        }
    }
    
    /**
     * Вычисляет среднюю скорость в км/ч
     */
    fun calculateAverageSpeed(distanceKm: Float, durationMs: Long): Float {
        return SpeedPaceCalculator.averageSpeedKmh(distanceKm, durationMs)
    }
    
    /**
     * Вычисляет темп в минутах на километр
     */
    fun calculatePaceKph(distanceKm: Float, durationMs: Long): Float {
        return SpeedPaceCalculator.segmentPaceMetric(durationMs, distanceKm, metric = true)
    }

    fun calculatePaceMph(distanceKm: Float, durationMs: Long): Float {
        return SpeedPaceCalculator.segmentPaceMetric(durationMs, distanceKm, metric = false)
    }
    
    /**
     * Вычисляет калории (приблизительно)
     */
    fun calculateCalories(distanceKm: Float, weightKg: Float = 70f): Int {
        return (distanceKm * weightKg).toInt()
    }
}
