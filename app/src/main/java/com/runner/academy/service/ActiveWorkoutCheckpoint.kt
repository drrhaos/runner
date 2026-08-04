package com.runner.academy.service

import android.content.Context
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.runner.academy.data.GpsStatus
import com.runner.academy.data.TrackPoint
import com.runner.academy.data.WorkoutSession
import com.runner.academy.data.WorkoutType
import org.osmdroid.util.GeoPoint
import java.io.File

/**
 * Disk snapshot of an in-progress workout so the session can survive process death
 * (swipe from Recents) and sticky service restart.
 */
data class ActiveWorkoutCheckpoint(
    val isTracking: Boolean = false,
    val isPaused: Boolean = false,
    val startTime: Long = 0L,
    val pauseTime: Long = 0L,
    val totalPauseDuration: Long = 0L,
    val currentTime: Long = 0L,
    val distance: Float = 0f,
    val avgPace: Float = 0f,
    val currentPace: Float = 0f,
    val avgSpeed: Float = 0f,
    val currentSpeed: Float = 0f,
    val calories: Int = 0,
    val gpsStatus: String = GpsStatus.SEARCHING.name,
    val trackDataPoints: List<TrackPoint> = emptyList(),
    val rawTrackDataPoints: List<TrackPoint> = emptyList(),
    val workoutType: String = WorkoutType.EASY_RUN.name,
    val modeSelection: String? = null,
    val intervalSegmentsJson: String? = null,
    val lastLocationTime: Long = 0L,
    val lastUpdateTime: Long = 0L,
    val savedAt: Long = 0L
) {
    fun toSession(): WorkoutSession {
        val points = trackDataPoints.map { GeoPoint(it.latitude, it.longitude) }
        val status = GpsStatus.entries.find { it.name == gpsStatus } ?: GpsStatus.SEARCHING
        val last = trackDataPoints.lastOrNull()
        val location = last?.let { point ->
            android.location.Location("checkpoint").apply {
                latitude = point.latitude
                longitude = point.longitude
                time = point.timestamp
                point.accuracy?.let { accuracy = it }
                point.speed?.let { speed = it }
                point.altitude?.let { altitude = it }
            }
        }
        return WorkoutSession(
            isTracking = isTracking,
            isPaused = isPaused,
            startTime = startTime,
            pauseTime = pauseTime,
            totalPauseDuration = totalPauseDuration,
            currentTime = currentTime,
            distance = distance,
            avgPace = avgPace,
            currentPace = currentPace,
            avgSpeed = avgSpeed,
            currentSpeed = currentSpeed,
            calories = calories,
            gpsStatus = status,
            trackPoints = points,
            trackDataPoints = trackDataPoints,
            rawTrackDataPoints = rawTrackDataPoints,
            currentLocation = location
        )
    }

    fun resolvedWorkoutType(): WorkoutType =
        WorkoutType.entries.find { it.name == workoutType } ?: WorkoutType.EASY_RUN

    companion object {
        fun fromSession(
            session: WorkoutSession,
            workoutType: WorkoutType,
            modeSelection: String?,
            intervalSegmentsJson: String?,
            lastLocationTime: Long,
            lastUpdateTime: Long
        ): ActiveWorkoutCheckpoint = ActiveWorkoutCheckpoint(
            isTracking = session.isTracking,
            isPaused = session.isPaused,
            startTime = session.startTime,
            pauseTime = session.pauseTime,
            totalPauseDuration = session.totalPauseDuration,
            currentTime = session.currentTime,
            distance = session.distance,
            avgPace = session.avgPace,
            currentPace = session.currentPace,
            avgSpeed = session.avgSpeed,
            currentSpeed = session.currentSpeed,
            calories = session.calories,
            gpsStatus = session.gpsStatus.name,
            trackDataPoints = session.trackDataPoints,
            rawTrackDataPoints = session.rawTrackDataPoints,
            workoutType = workoutType.name,
            modeSelection = modeSelection,
            intervalSegmentsJson = intervalSegmentsJson,
            lastLocationTime = lastLocationTime,
            lastUpdateTime = lastUpdateTime,
            savedAt = System.currentTimeMillis()
        )
    }
}

class ActiveWorkoutStore(context: Context) {
    private val file = File(context.filesDir, FILE_NAME)
    private val gson: Gson = GsonBuilder().create()

    @Synchronized
    fun save(checkpoint: ActiveWorkoutCheckpoint) {
        try {
            val tmp = File(file.parentFile, "$FILE_NAME.tmp")
            tmp.writeText(gson.toJson(checkpoint))
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to save workout checkpoint", e)
        }
    }

    @Synchronized
    fun load(): ActiveWorkoutCheckpoint? {
        if (!file.exists()) return null
        return try {
            val json = file.readText()
            if (json.isBlank()) null else gson.fromJson(json, ActiveWorkoutCheckpoint::class.java)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to load workout checkpoint", e)
            null
        }
    }

    @Synchronized
    fun clear() {
        try {
            if (file.exists()) file.delete()
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Failed to clear workout checkpoint", e)
        }
    }

    companion object {
        private const val FILE_NAME = "active_workout_checkpoint.json"
        private const val TAG = "ActiveWorkoutStore"
    }
}
