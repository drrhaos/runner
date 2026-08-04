package com.runner.academy.ui.tracking

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.location.Location
import android.os.IBinder
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.runner.academy.data.LocationSource
import com.runner.academy.data.TrackData
import com.runner.academy.data.TrackPoint
import com.runner.academy.data.Workout
import com.runner.academy.data.WorkoutRepository
import com.runner.academy.data.WorkoutSession
import com.runner.academy.data.WorkoutState
import com.runner.academy.data.WorkoutType
import com.runner.academy.service.GpsLocationProcessor
import com.runner.academy.service.IntervalCursor
import com.runner.academy.service.WorkoutTrackingService
import com.runner.academy.util.GpsFilter
import com.runner.academy.util.IntervalSegmentsJson
import com.runner.academy.util.SpeedPaceCalculator
import com.runner.academy.util.TrackDataJson
import com.runner.academy.util.UserPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Date

/**
 * UI mirror of [WorkoutTrackingService]. Does not run GPS or workout timers locally —
 * the foreground service is the sole owner of an active session.
 */
class WorkoutTrackingViewModel(
    private val repository: WorkoutRepository,
    private val application: Application
) : ViewModel() {

    private val _workoutSession = MutableStateFlow(WorkoutSession())
    val workoutSession: StateFlow<WorkoutSession> = _workoutSession.asStateFlow()

    private val _workoutState = MutableStateFlow(WorkoutState.NOT_STARTED)
    val workoutState: StateFlow<WorkoutState> = _workoutState.asStateFlow()

    /**
     * null = first open (auto-pick today's plan if any).
     * Otherwise the user's explicit spinner choice.
     */
    private val _modeSelection = MutableStateFlow<TrackingModeSelection?>(null)
    val modeSelection: StateFlow<TrackingModeSelection?> = _modeSelection.asStateFlow()

    fun setModeSelection(selection: TrackingModeSelection) {
        _modeSelection.value = selection
        trackingService?.updateUiMetadata(
            modeSelection = TrackingModeSelectionCodec.encode(selection),
            intervalSegmentsJson = null,
            intervalCursor = null
        )
    }

    /**
     * Snapshot of interval segments for the active session. Survives Fragment
     * recreation (rotation / nav) so the plan is not lost when the spinner
     * briefly falls back to Easy Run while templates reload.
     */
    private val _activeIntervalSegments =
        MutableStateFlow<List<com.runner.academy.data.WorkoutTemplateSegment>?>(null)
    val activeIntervalSegments:
        StateFlow<List<com.runner.academy.data.WorkoutTemplateSegment>?> =
        _activeIntervalSegments.asStateFlow()

    fun setActiveIntervalSegments(
        segments: List<com.runner.academy.data.WorkoutTemplateSegment>?
    ) {
        _activeIntervalSegments.value = segments?.takeIf { it.isNotEmpty() }
        trackingService?.updateUiMetadata(
            modeSelection = null,
            intervalSegmentsJson = _activeIntervalSegments.value?.let {
                IntervalSegmentsJson.toJson(it)
            },
            intervalCursor = null
        )
    }

    fun clearActiveIntervalSegments() {
        _activeIntervalSegments.value = null
    }

    fun updateIntervalCursor(cursor: IntervalCursor) {
        trackingService?.updateUiMetadata(
            modeSelection = null,
            intervalSegmentsJson = null,
            intervalCursor = cursor
        )
    }

    fun getIntervalCursor(): IntervalCursor? = trackingService?.getIntervalCursor()

    private var trackingService: WorkoutTrackingService? = null
    private var isServiceBound = false
    private val serviceBound = MutableStateFlow(false)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? WorkoutTrackingService.WorkoutTrackingBinder ?: return
            trackingService = binder.getService()
            isServiceBound = true
            serviceBound.value = true

            trackingService?.setSessionUpdateCallback { session ->
                _workoutSession.value = session
                updateWorkoutState()
            }
            trackingService?.let { adoptServiceSession(it) }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            trackingService = null
            isServiceBound = false
            serviceBound.value = false
        }
    }

    fun initializeService() {
        if (isServiceBound) {
            trackingService?.let { adoptServiceSession(it) }
            return
        }
        val intent = Intent(application, WorkoutTrackingService::class.java)
        application.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    /**
     * Waits until the tracking service is bound. Call before start/pause/resume
     * instead of sleeping.
     */
    suspend fun awaitServiceBound(timeoutMs: Long = BIND_TIMEOUT_MS): Boolean {
        if (isServiceBound && trackingService != null) return true
        initializeService()
        val ready = withTimeoutOrNull(timeoutMs) {
            serviceBound.first { it }
        } != null
        return ready && trackingService != null
    }

    suspend fun startWorkoutWhenReady(workoutType: WorkoutType = WorkoutType.EASY_RUN): Boolean {
        if (!awaitServiceBound()) {
            android.util.Log.e(TAG, "Cannot start workout: service bind timed out")
            return false
        }
        startWorkout(workoutType)
        return true
    }

    private fun adoptServiceSession(svc: WorkoutTrackingService) {
        val serviceSession = svc.getCurrentSession()
        _workoutSession.value = serviceSession
        if (serviceSession.isTracking || serviceSession.isPaused) {
            restoreUiMetadataFromService(svc)
        }
        updateWorkoutState()
    }

    private fun restoreUiMetadataFromService(svc: WorkoutTrackingService) {
        svc.getModeSelectionKey()?.let { key ->
            TrackingModeSelectionCodec.decode(key)?.let { selection ->
                if (_modeSelection.value == null) {
                    _modeSelection.value = selection
                }
            }
        }
        if (_activeIntervalSegments.value.isNullOrEmpty()) {
            svc.getIntervalSegmentsJson()?.let { json ->
                val segments = IntervalSegmentsJson.parse(json)
                if (segments.isNotEmpty()) {
                    _activeIntervalSegments.value = segments
                }
            }
        }
    }

    fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            application,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                application,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
    }

    fun startWorkout(workoutType: WorkoutType = WorkoutType.EASY_RUN) {
        val svc = trackingService
        if (!isServiceBound || svc == null) {
            android.util.Log.e(TAG, "startWorkout ignored: service not bound")
            return
        }
        val existing = svc.getCurrentSession()
        if (existing.isTracking || existing.isPaused) {
            adoptServiceSession(svc)
            return
        }
        val intent = Intent(application, WorkoutTrackingService::class.java).apply {
            action = WorkoutTrackingService.ACTION_START_WORKOUT
            putExtra(WorkoutTrackingService.EXTRA_WORKOUT_TYPE, workoutType)
            TrackingModeSelectionCodec.encode(_modeSelection.value)?.let {
                putExtra(WorkoutTrackingService.EXTRA_MODE_SELECTION, it)
            }
            _activeIntervalSegments.value?.let { segments ->
                IntervalSegmentsJson.toJson(segments)?.let { json ->
                    putExtra(WorkoutTrackingService.EXTRA_INTERVAL_SEGMENTS_JSON, json)
                }
            }
        }
        application.startForegroundService(intent)
    }

    fun pauseWorkout() {
        if (!isServiceBound || trackingService == null) {
            android.util.Log.w(TAG, "pauseWorkout ignored: service not bound")
            return
        }
        val intent = Intent(application, WorkoutTrackingService::class.java).apply {
            action = WorkoutTrackingService.ACTION_PAUSE_WORKOUT
        }
        application.startService(intent)
    }

    fun resumeWorkout() {
        if (!isServiceBound || trackingService == null) {
            android.util.Log.w(TAG, "resumeWorkout ignored: service not bound")
            return
        }
        val intent = Intent(application, WorkoutTrackingService::class.java).apply {
            action = WorkoutTrackingService.ACTION_RESUME_WORKOUT
        }
        application.startService(intent)
    }

    fun stopWorkout() {
        if (isServiceBound && trackingService != null) {
            val intent = Intent(application, WorkoutTrackingService::class.java).apply {
                action = WorkoutTrackingService.ACTION_STOP_WORKOUT
            }
            application.startService(intent)
            unbindTrackingService()
        } else {
            _workoutSession.value = _workoutSession.value.copy(
                isTracking = false,
                isPaused = false
            )
            _workoutState.value = WorkoutState.STOPPED
        }
    }

    fun resetWorkout() {
        unbindTrackingService()
        try {
            val stopIntent = Intent(application, WorkoutTrackingService::class.java).apply {
                action = WorkoutTrackingService.ACTION_STOP_WORKOUT
            }
            application.startService(stopIntent)
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Failed to send stop intent on reset", e)
        }
        _workoutSession.value = WorkoutSession()
        _workoutState.value = WorkoutState.NOT_STARTED
        clearActiveIntervalSegments()
    }

    private fun updateWorkoutState() {
        val session = _workoutSession.value
        val oldState = _workoutState.value
        val newState = when {
            !session.isTracking && !session.isPaused -> WorkoutState.NOT_STARTED
            session.isTracking && !session.isPaused -> WorkoutState.RUNNING
            session.isTracking && session.isPaused -> WorkoutState.PAUSED
            !session.isTracking && session.isPaused -> WorkoutState.PAUSED
            else -> {
                android.util.Log.w(
                    TAG,
                    "Unexpected state: isTracking=${session.isTracking}, isPaused=${session.isPaused}"
                )
                WorkoutState.NOT_STARTED
            }
        }
        if (oldState != newState) {
            _workoutState.value = newState
        }
    }

    fun formatTime(milliseconds: Long): String =
        com.runner.academy.util.FormatUtils.formatTime(milliseconds)

    fun formatSpeed(speedKmh: Float): String =
        com.runner.academy.util.FormatUtils.formatSpeed(speedKmh)

    fun formatPace(paceMinutesPerKm: Float): String =
        com.runner.academy.util.FormatUtils.formatPace(paceMinutesPerKm)

    suspend fun saveWorkoutToDatabase(
        workoutType: WorkoutType = WorkoutType.EASY_RUN,
        manualDistanceKm: Float? = null,
        intervalSegmentsJson: String? = null
    ): Long? {
        val session = _workoutSession.value
        if (session.currentTime <= 0) return null

        val sourcePoints = if (session.rawTrackDataPoints.isNotEmpty()) {
            session.rawTrackDataPoints
        } else {
            session.trackDataPoints
        }
        val sanitizedPoints = sanitizeTrackPoints(sourcePoints)
        val hasTrack = sanitizedPoints.size >= 2

        val totalDistanceMeters = when {
            manualDistanceKm != null && manualDistanceKm >= 0f -> manualDistanceKm * 1000f
            hasTrack -> SpeedPaceCalculator.totalDistanceMeters(sanitizedPoints)
            session.distance > 0f -> session.distance * 1000f
            else -> 0f
        }
        if (totalDistanceMeters <= 0f && session.currentTime <= 0) {
            android.util.Log.w(TAG, "No distance and no duration to save")
            return null
        }
        val totalDistanceKm = totalDistanceMeters / 1000f
        val durationMs = session.currentTime
        val avgSpeedMps = if (totalDistanceMeters > 0f) {
            SpeedPaceCalculator.averageSpeedMs(totalDistanceMeters, durationMs)
        } else {
            0f
        }
        val avgPace = if (totalDistanceKm > 0f) {
            SpeedPaceCalculator.overallAveragePace(
                totalDistanceMeters = totalDistanceKm * 1000.0,
                totalDurationSeconds = durationMs / 1000.0
            )
        } else {
            0f
        }
        val maxSpeedMps = if (hasTrack && manualDistanceKm == null) {
            SpeedPaceCalculator.maxDerivedSpeedMs(sanitizedPoints)
        } else {
            0f
        }

        val userPrefs = (application as? com.runner.academy.RunnerApplication)?.container?.userPreferences
            ?: UserPreferences(application)
        val calories = com.runner.academy.util.FormatUtils.calculateCalories(
            totalDistanceKm,
            userPrefs.userWeight
        )

        val trackDataJson = if (hasTrack && manualDistanceKm == null && totalDistanceMeters > 0f) {
            TrackDataJson.toJson(
                TrackData(
                    points = sanitizedPoints,
                    totalDistance = totalDistanceMeters,
                    totalDuration = durationMs,
                    avgSpeed = avgSpeedMps,
                    maxSpeed = maxSpeedMps,
                    startTime = session.startTime,
                    endTime = System.currentTimeMillis()
                )
            )
        } else {
            null
        }

        val workout = Workout(
            date = Date(session.startTime),
            distance = totalDistanceKm,
            duration = durationMs,
            avgPace = avgPace,
            calories = calories,
            notes = null,
            type = workoutType,
            trackData = trackDataJson,
            intervalSegmentsJson = intervalSegmentsJson
        )

        return try {
            com.runner.academy.util.ErrorHandler.retryWithBackoff(
                maxRetries = 3,
                initialDelay = 1000L
            ) {
                repository.insertWorkout(workout)
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error saving workout after retries: ${e.message}", e)
            com.runner.academy.util.ErrorHandler.handleSaveError(application, e, false)
            null
        }
    }

    private fun sanitizeTrackPoints(rawPoints: List<TrackPoint>): List<TrackPoint> {
        if (rawPoints.isEmpty()) return emptyList()
        val result = mutableListOf<TrackPoint>()
        var previousLocation: Location? = null

        for (point in rawPoints) {
            val rawLocation = trackPointToLocation(point)
            val forceGap = GpsFilter.isGapResume(previousLocation, rawLocation) || point.afterGap
            val filteredLocation = GpsFilter.filterGpsOutlier(
                rawLocation,
                previousLocation,
                forceGapResume = forceGap
            ) ?: continue

            val afterGap = forceGap && previousLocation != null
            if (!afterGap && previousLocation != null) {
                val segmentDistance = filteredLocation.distanceTo(previousLocation)
                if (segmentDistance < GpsLocationProcessor.MIN_POINT_DISTANCE_METERS) {
                    continue
                }
            }

            result.add(
                point.copy(
                    latitude = filteredLocation.latitude,
                    longitude = filteredLocation.longitude,
                    accuracy = filteredLocation.accuracy,
                    speed = filteredLocation.speed,
                    altitude = filteredLocation.altitude,
                    afterGap = afterGap,
                    source = point.source.ifBlank { LocationSource.GPS.name }
                )
            )
            previousLocation = filteredLocation
        }
        return result
    }

    private fun trackPointToLocation(point: TrackPoint): Location {
        return Location("track").apply {
            latitude = point.latitude
            longitude = point.longitude
            time = if (point.timestamp > 0) point.timestamp else System.currentTimeMillis()
            accuracy = point.accuracy ?: 50f
            speed = point.speed ?: 0f
            point.altitude?.let { altitude = it }
        }
    }

    private fun unbindTrackingService() {
        if (!isServiceBound) return
        try {
            trackingService?.setSessionUpdateCallback(null)
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Failed to clear session callback", e)
        }
        try {
            application.unbindService(serviceConnection)
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Failed to unbind tracking service", e)
        }
        isServiceBound = false
        trackingService = null
        serviceBound.value = false
    }

    fun cleanup() {
        unbindTrackingService()
    }

    override fun onCleared() {
        super.onCleared()
        cleanup()
    }

    companion object {
        private const val TAG = "WorkoutTrackingViewModel"
        private const val BIND_TIMEOUT_MS = 5_000L
    }
}

object TrackingModeSelectionCodec {
    fun encode(selection: TrackingModeSelection?): String? = when (selection) {
        TrackingModeSelection.EasyRun -> "easy"
        TrackingModeSelection.PlanToday -> "plan"
        is TrackingModeSelection.Template -> "template:${selection.templateId}"
        null -> null
    }

    fun decode(raw: String?): TrackingModeSelection? {
        if (raw.isNullOrBlank()) return null
        return when {
            raw == "easy" -> TrackingModeSelection.EasyRun
            raw == "plan" -> TrackingModeSelection.PlanToday
            raw.startsWith("template:") -> {
                val id = raw.removePrefix("template:").toLongOrNull() ?: return null
                TrackingModeSelection.Template(id)
            }
            else -> null
        }
    }
}

class WorkoutTrackingViewModelFactory(
    private val repository: WorkoutRepository,
    private val application: Application
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(WorkoutTrackingViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return WorkoutTrackingViewModel(repository, application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
