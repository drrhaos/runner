package com.example.runner.data

import android.location.Location
import org.osmdroid.util.GeoPoint

data class WorkoutSession(
    val isTracking: Boolean = false,
    val isPaused: Boolean = false,
    val startTime: Long = 0,
    val pauseTime: Long = 0,
    val totalPauseDuration: Long = 0,
    val currentTime: Long = 0,
    val distance: Float = 0f,
    val avgPace: Float = 0f, // средний темп в минутах на километр
    val currentPace: Float = 0f, // текущий темп в минутах на километр
    val avgSpeed: Float = 0f,
    val currentSpeed: Float = 0f,
    val heartRate: Int = 0,
    val calories: Int = 0,
    val gpsStatus: GpsStatus = GpsStatus.SEARCHING,
    val trackPoints: List<GeoPoint> = emptyList(), // для отображения на карте
    val trackDataPoints: List<TrackPoint> = emptyList(), // для сохранения в JSON
    val rawTrackDataPoints: List<TrackPoint> = emptyList(), // все точки без фильтрации для последующей проверки
    val currentLocation: Location? = null
)

enum class GpsStatus {
    SEARCHING,
    WEAK,
    MEDIUM,
    STRONG,
    FOUND,
    LOST,
    DENIED
}

enum class WorkoutState {
    NOT_STARTED,    // 1. не запущена
    RUNNING,        // 2. запущена
    PAUSED,         // 3. пауза
    STOPPED         // 4. остановлена
}
