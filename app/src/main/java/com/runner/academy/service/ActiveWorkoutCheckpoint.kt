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

/** Persisted IntervalEngine cursor so mid-workout restore keeps segment progress. */
data class IntervalCursor(
    val segmentIndex: Int = 0,
    val segmentStartElapsedMs: Long = 0L,
    val segmentStartDistanceM: Float = 0f,
    val lastAnnouncedIndex: Int = -1,
    val lastUpcomingWarnedIndex: Int = -1
)

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
    val intervalSegmentIndex: Int = 0,
    val intervalSegmentStartElapsedMs: Long = 0L,
    val intervalSegmentStartDistanceM: Float = 0f,
    val intervalLastAnnouncedIndex: Int = -1,
    val intervalLastUpcomingWarnedIndex: Int = -1,
    val lastLocationTime: Long = 0L,
    val lastUpdateTime: Long = 0L,
    val savedAt: Long = 0L,
    /** Steps since the start when saved; null without steps (and in older checkpoints). */
    val steps: Int? = null,
    /** Step distance of an open false-signal episode, already in [distance]. */
    val pendingStepMeters: Float = 0f,
    /** See [WorkoutSession.strideModelState]. */
    val strideModelState: String? = null,
    /** The session clock; null in checkpoints written before it (see [clockState]). */
    val clock: SessionClockState? = null,
    /** See [WorkoutSession.movingTime]; null in older checkpoints (= [currentTime]). */
    val movingTime: Long? = null
) {
    /**
     * The clock to continue from. An older checkpoint knows only the summed manual pause time:
     * elapsed stays now − start − [totalPauseDuration], as before; an open pause continues.
     */
    fun clockState(): SessionClockState = clock?.takeIf { it.startedAt > 0L } ?: SessionClockState(
        startedAt = startTime,
        manualPausedAt = pauseTime.takeIf { isPaused && it > 0L },
        legacyManualPauseMs = totalPauseDuration
    )

    fun intervalCursor(): IntervalCursor? {
        if (intervalSegmentsJson.isNullOrBlank()) return null
        return IntervalCursor(
            segmentIndex = intervalSegmentIndex,
            segmentStartElapsedMs = intervalSegmentStartElapsedMs,
            segmentStartDistanceM = intervalSegmentStartDistanceM,
            lastAnnouncedIndex = intervalLastAnnouncedIndex,
            lastUpcomingWarnedIndex = intervalLastUpcomingWarnedIndex
        )
    }
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
            currentLocation = location,
            strideModelState = strideModelState,
            movingTime = movingTime ?: currentTime,
            clock = clockState()
        ).let { session ->
            val restoredClock = SessionClock(session.clock)
            session.copy(autoPaused = restoredClock.autoPaused, everAutoPaused = restoredClock.everAutoPaused)
        }
    }

    fun resolvedWorkoutType(): WorkoutType =
        WorkoutType.entries.find { it.name == workoutType } ?: WorkoutType.EASY_RUN

    companion object {
        fun fromSession(
            session: WorkoutSession,
            workoutType: WorkoutType,
            modeSelection: String?,
            intervalSegmentsJson: String?,
            intervalCursor: IntervalCursor?,
            lastLocationTime: Long,
            lastUpdateTime: Long,
            steps: Int? = null,
            pendingStepMeters: Float = 0f
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
            intervalSegmentIndex = intervalCursor?.segmentIndex ?: 0,
            intervalSegmentStartElapsedMs = intervalCursor?.segmentStartElapsedMs ?: 0L,
            intervalSegmentStartDistanceM = intervalCursor?.segmentStartDistanceM ?: 0f,
            intervalLastAnnouncedIndex = intervalCursor?.lastAnnouncedIndex ?: -1,
            intervalLastUpcomingWarnedIndex = intervalCursor?.lastUpcomingWarnedIndex ?: -1,
            lastLocationTime = lastLocationTime,
            lastUpdateTime = lastUpdateTime,
            savedAt = System.currentTimeMillis(),
            steps = steps,
            pendingStepMeters = pendingStepMeters,
            strideModelState = session.strideModelState,
            clock = session.clock,
            movingTime = session.movingTime
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

    /**
     * A workout is being recorded: the checkpoint is running or paused and was saved within
     * [LIVE_WINDOW_MS]. A running service saves it every [WorkoutTrackingService]
     * checkpoint interval; an older one is left by a restore the system refused (kept until the
     * app is opened) or by a pause long enough for background work. Reads the file: call off
     * the main thread.
     */
    fun isWorkoutActive(now: Long = System.currentTimeMillis()): Boolean {
        val checkpoint = load() ?: return false
        return (checkpoint.isTracking || checkpoint.isPaused) && now - checkpoint.savedAt < LIVE_WINDOW_MS
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
        /** Far above the save interval of a running workout (15 s). */
        const val LIVE_WINDOW_MS = 10 * 60_000L
        private const val FILE_NAME = "active_workout_checkpoint.json"
        private const val TAG = "ActiveWorkoutStore"
    }
}
