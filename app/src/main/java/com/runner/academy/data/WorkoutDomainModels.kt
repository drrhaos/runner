package com.runner.academy.data

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
    val currentLocation: Location? = null,
    /**
     * The run's frozen stride model ([com.runner.academy.util.StrideModel.serialize]) that bridges
     * dropped stretches by steps; the save path rebuilds the same estimator from it so the saved
     * distance matches the live one. Null when steps are not used in this run.
     */
    val strideModelState: String? = null,
    /**
     * Step distance already in [distance] that no kept point carries yet (an open silence or
     * false signal, [com.runner.academy.service.GpsLocationProcessor.pendingStepMeters]): saved as
     * the track's tail if the run stops before a fix closes it.
     */
    val openStepMeters: Float = 0f
)

enum class GpsStatus {
    SEARCHING,
    WEAK,
    MEDIUM,
    STRONG,
    FOUND,
    LOST,
    DENIED,
    /**
     * Fixes arrive but are false (spoofing / jamming): the position is not shown and the
     * distance is counted from steps until a good fix returns.
     */
    UNRELIABLE
}

enum class WorkoutState {
    NOT_STARTED,    // 1. не запущена
    RUNNING,        // 2. запущена
    PAUSED,         // 3. пауза
    STOPPED         // 4. остановлена
}
