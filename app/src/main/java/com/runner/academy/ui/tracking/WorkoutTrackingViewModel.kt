package com.runner.academy.ui.tracking

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.location.Location
import android.os.IBinder
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.runner.academy.util.GpsLocationClient
import com.runner.academy.data.TrackPoint
import com.runner.academy.data.WorkoutRepository
import com.runner.academy.data.WorkoutSession
import com.runner.academy.data.WorkoutState
import com.runner.academy.data.WorkoutType
import com.runner.academy.service.IntervalCursor
import com.runner.academy.service.WorkoutTrackingService
import com.runner.academy.util.IntervalSegmentsJson
import com.runner.academy.util.StrideModel
import com.runner.academy.util.UserPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

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

    /** Latest usable pre-start fix from the map; applied when Start binds the service. */
    private var pendingPreStartLocation: Location? = null

    fun seedPreStartLocation(location: Location?) {
        pendingPreStartLocation = location
        location?.let { trackingService?.seedPreStartLocation(it) }
    }

    fun clearActiveIntervalSegments() {
        _activeIntervalSegments.value = null
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

    fun hasLocationPermission(): Boolean = GpsLocationClient.hasPrecisePermission(application)

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
        pendingPreStartLocation?.let { loc ->
            svc.seedPreStartLocation(loc)
            pendingPreStartLocation = null
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
            // The moment of Stop: the save path takes duration and moving time from it, however
            // long the save dialog stays open (no more updates arrive once unbound)
            _workoutSession.value = LiveWorkoutBuilder.stopped(_workoutSession.value, System.currentTimeMillis())
        } else {
            _workoutSession.value = LiveWorkoutBuilder.stopped(_workoutSession.value, System.currentTimeMillis())
                .copy(isTracking = false, isPaused = false)
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

        val userPrefs = (application as? com.runner.academy.RunnerApplication)?.container?.userPreferences
            ?: UserPreferences(application)
        // The run's frozen stride, as the live path used it: the same bridges, the same distance
        val stepDistance = session.strideModelState?.let { StrideModel.frozenEstimatorOf(it, userPrefs.userHeight) }
        // The clock was stopped at Stop (stopWorkout); duration and moving time are its own
        val workout = LiveWorkoutBuilder.build(
            session = session,
            workoutType = workoutType,
            manualDistanceKm = manualDistanceKm,
            intervalSegmentsJson = intervalSegmentsJson,
            stepDistance = stepDistance,
            userWeightKg = userPrefs.userWeight,
            stopAt = System.currentTimeMillis()
        )
        if (workout == null) {
            android.util.Log.w(TAG, "No distance and no duration to save")
            return null
        }

        return try {
            val id = com.runner.academy.util.ErrorHandler.retryWithBackoff(
                maxRetries = 3,
                initialDelay = 1000L
            ) {
                repository.insertWorkout(workout)
            }
            // A rename is safe while the service still flushes: the open handle follows the file
            repository.attachGpsDiagnostics(session.startTime, id)
            id
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error saving workout after retries: ${e.message}", e)
            com.runner.academy.util.ErrorHandler.handleSaveError(application, e, false)
            null
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
