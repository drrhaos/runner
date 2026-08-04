package com.runner.academy.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.runner.academy.data.GpsStatus
import com.runner.academy.data.WorkoutSession
import com.runner.academy.data.WorkoutType
import com.runner.academy.util.GpsConfig
import com.runner.academy.util.GpsFilter
import com.runner.academy.util.UserPreferences
import com.google.android.gms.location.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service that coordinates workout tracking.
 *
 * Delegates to:
 *  - [GpsLocationProcessor] for GPS filtering and point processing
 *  - [WorkoutNotificationManager] for foreground notification lifecycle
 *  - [WorkoutSessionManager] for session state, metrics, and timer
 *
 * The service itself handles:
 *  - Android Service lifecycle (onCreate, onDestroy, onBind)
 *  - Binding with LocationManager / FusedLocationProvider
 *  - Coroutine scope and periodic job management
 *  - Adaptive GPS interval updates
 */
class WorkoutTrackingService : Service() {

    companion object {
        const val CHANNEL_ID = "WorkoutTrackingChannel"
        const val NOTIFICATION_ID = 1
        const val ACTION_START_WORKOUT = "START_WORKOUT"
        const val ACTION_PAUSE_WORKOUT = "PAUSE_WORKOUT"
        const val ACTION_RESUME_WORKOUT = "RESUME_WORKOUT"
        const val ACTION_STOP_WORKOUT = "STOP_WORKOUT"
        const val ACTION_RESTORE_WORKOUT = "RESTORE_WORKOUT"
        const val EXTRA_WORKOUT_TYPE = "WORKOUT_TYPE"
        const val EXTRA_MODE_SELECTION = "MODE_SELECTION"
        const val EXTRA_INTERVAL_SEGMENTS_JSON = "INTERVAL_SEGMENTS_JSON"
        const val NO_LOCATION_UPDATE_TIMEOUT_MS = 5000L
        const val PERIODIC_LOCATION_REQUEST_INTERVAL_MS = 1000L
        const val WORKOUT_TIMER_INTERVAL_MS = 1000L
        private const val CHECKPOINT_SAVE_MIN_INTERVAL_MS = 5_000L
    }

    // Extracted component instances
    private val gpsProcessor = GpsLocationProcessor()
    private lateinit var notificationManager: WorkoutNotificationManager
    private val sessionManager = WorkoutSessionManager()
    private lateinit var activeWorkoutStore: ActiveWorkoutStore

    // Location provider bindings
    private var fusedLocationClient: FusedLocationProviderClient? = null
    private var locationCallback: LocationCallback? = null
    private var currentLocationRequest: LocationRequest? = null

    // Tracking state
    private var isCurrentlyTracking = false
    private var lastLocation: Location? = null
    private var selectedWorkoutType: WorkoutType = WorkoutType.EASY_RUN
    private var modeSelectionKey: String? = null
    private var intervalSegmentsJson: String? = null
    private var intervalCursor: IntervalCursor? = null
    private var lastCheckpointSaveAt: Long = 0L

    // Coroutine management
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var periodicLocationJob: Job? = null
    private var lastLocationTime: Long = 0
    private var lastAppliedAdaptiveIntervalMs: Long = -1L
    private var workoutTimerJob: Job? = null

    // User preferences for calorie calculation
    private lateinit var userPreferences: UserPreferences

    inner class WorkoutTrackingBinder : Binder() {
        fun getService(): WorkoutTrackingService = this@WorkoutTrackingService
    }
    private val binder = WorkoutTrackingBinder()

    // ------------------------------------------------------------------
    // Service lifecycle
    // ------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        notificationManager = WorkoutNotificationManager(this)
        notificationManager.createNotificationChannel()
        userPreferences = UserPreferences(this)
        activeWorkoutStore = ActiveWorkoutStore(this)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        setupLocationCallback()
        sessionManager.onSessionChanged = { session ->
            sessionUpdateCallback?.invoke(session)
            maybeSaveCheckpoint(session, force = session.isPaused)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_WORKOUT -> {
                selectedWorkoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getSerializableExtra(EXTRA_WORKOUT_TYPE, WorkoutType::class.java) ?: WorkoutType.EASY_RUN
                } else {
                    @Suppress("DEPRECATION")
                    intent.getSerializableExtra(EXTRA_WORKOUT_TYPE) as? WorkoutType ?: WorkoutType.EASY_RUN
                }
                modeSelectionKey = intent.getStringExtra(EXTRA_MODE_SELECTION)
                intervalSegmentsJson = intent.getStringExtra(EXTRA_INTERVAL_SEGMENTS_JSON)
                startWorkout()
            }
            ACTION_PAUSE_WORKOUT -> pauseWorkout()
            ACTION_RESUME_WORKOUT -> resumeWorkout()
            ACTION_STOP_WORKOUT -> stopWorkout()
            ACTION_RESTORE_WORKOUT, null -> {
                // Sticky restart (null) or explicit restore after process death
                if (!restoreFromCheckpoint()) {
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder {
        // Bound after process death without sticky start yet — kick restore if needed
        requestRestoreIfCheckpointExists()
        return binder
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        maybeSaveCheckpoint(sessionManager.getSession(), force = true)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        val session = sessionManager.getSession()
        if (session.isTracking || session.isPaused) {
            maybeSaveCheckpoint(session, force = true)
        }
        super.onDestroy()
        stopWorkoutTimer()
        stopLocationUpdates()
        stopPeriodicLocationRequest()
        serviceScope.cancel()
    }

    // ------------------------------------------------------------------
    // Location callback & processing
    // ------------------------------------------------------------------

    private fun setupLocationCallback() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    updateLocation(location)
                }
            }
        }
    }

    private fun updateLocation(location: Location) {
        // Dispatch to main thread if called from background
        if (Thread.currentThread().name != "main") {
            serviceScope.launch { updateLocation(location) }
            return
        }

        if (isCurrentlyTracking && !sessionManager.getSession().isPaused) {
            val session = sessionManager.getSession()
            val resumeAfterGap = session.gpsStatus == GpsStatus.LOST ||
                (lastLocationTime > 0L &&
                    System.currentTimeMillis() - lastLocationTime >= GpsFilter.GAP_RESUME_THRESHOLD_MS)

            val result = gpsProcessor.processLocation(
                location,
                lastLocation,
                selectedWorkoutType,
                session.trackPoints.toMutableList(),
                session.trackDataPoints.toMutableList(),
                session.rawTrackDataPoints.toMutableList(),
                resumeAfterGap = resumeAfterGap
            )

            when (result) {
                null -> { /* point filtered out entirely */ }
                is GpsLocationProcessor.ProcessResult.Accepted -> {
                    val segmentDistanceMeters = result.segmentDistanceMeters
                    sessionManager.updateMetricsFromLocation(
                        segmentDistanceMeters = segmentDistanceMeters,
                        trackPoints = result.trackPoints,
                        trackDataPoints = result.trackDataPoints,
                        rawTrackDataPoints = result.rawTrackDataPoints,
                        userWeightKg = userPreferences.userWeight,
                        currentLocation = result.filteredLocation
                    )

                    // Update adaptive GPS interval every 10 points
                    if (result.trackPoints.size % 10 == 0) {
                        updateLocationRequestInterval(sessionManager.getSession().currentSpeed)
                    }

                    lastLocation = result.filteredLocation
                    lastLocationTime = System.currentTimeMillis()
                }

                is GpsLocationProcessor.ProcessResult.Rejected -> {
                    if (result.refreshGapClock) {
                        lastLocationTime = System.currentTimeMillis()
                        // Near-duplicate fix: move map tip / icon without committing a track point
                        sessionManager.updateLocationOnly(
                            currentLocation = location,
                            rawTrackDataPoints = result.rawTrackDataPoints
                        )
                    } else {
                        // Outlier: keep tip on last accepted fix so the line does not jump
                        sessionManager.updateLocationOnly(
                            currentLocation = lastLocation ?: location,
                            rawTrackDataPoints = result.rawTrackDataPoints
                        )
                    }
                }
            }
        } else {
            val (rawPoints, currentLoc) = gpsProcessor.processLocationWhenNotTracking(
                location,
                sessionManager.getSession().rawTrackDataPoints,
                isCurrentlyTracking
            )
            sessionManager.updateLocationWhenNotActive(
                currentLocation = currentLoc ?: location,
                rawTrackDataPoints = rawPoints
            )
        }

        notificationManager.updateNotification(sessionManager.getSession())
    }

    // ------------------------------------------------------------------
    // Workout lifecycle
    // ------------------------------------------------------------------

    fun startWorkout() {
        if (sessionManager.getSession().isTracking && sessionManager.getSession().isPaused) {
            android.util.Log.w("WorkoutTrackingService", "startWorkout ignored: workout is paused, use resume")
            return
        }

        if (isCurrentlyTracking && sessionManager.getSession().isTracking && !sessionManager.getSession().isPaused) {
            android.util.Log.w("WorkoutTrackingService", "startWorkout ignored: workout already running")
            return
        }

        val hasPermission = hasLocationPermission()
        if (!hasPermission) {
            android.util.Log.w("WorkoutTrackingService", "No location permission granted, starting workout without GPS")
        }

        lastLocation = null
        lastAppliedAdaptiveIntervalMs = -1L
        isCurrentlyTracking = true
        lastLocationTime = 0L

        val initialGpsStatus = if (hasPermission) GpsStatus.SEARCHING else GpsStatus.DENIED
        sessionManager.startNewSession(initialGpsStatus = initialGpsStatus)
        maybeSaveCheckpoint(sessionManager.getSession(), force = true)

        if (hasPermission) {
            startLocationUpdates()
            startPeriodicLocationRequest()
        }
        startWorkoutTimer()
        startForeground(NOTIFICATION_ID, notificationManager.buildNotification(sessionManager.getSession()))
        notificationManager.updateNotification(sessionManager.getSession(), force = true)
    }

    fun pauseWorkout() {
        isCurrentlyTracking = false
        sessionManager.pause()
        stopPeriodicLocationRequest()
        stopWorkoutTimer()
        notificationManager.updateNotification(sessionManager.getSession(), force = true)
        maybeSaveCheckpoint(sessionManager.getSession(), force = true)
    }

    fun resumeWorkout() {
        isCurrentlyTracking = true
        sessionManager.resume()
        if (hasLocationPermission()) {
            startLocationUpdates()
            startPeriodicLocationRequest()
        }
        startWorkoutTimer()
        notificationManager.updateNotification(sessionManager.getSession(), force = true)
        maybeSaveCheckpoint(sessionManager.getSession(), force = true)
    }

    fun stopWorkout() {
        isCurrentlyTracking = false
        lastAppliedAdaptiveIntervalMs = -1L
        sessionManager.stop()
        activeWorkoutStore.clear()
        modeSelectionKey = null
        intervalSegmentsJson = null
        intervalCursor = null
        stopLocationUpdates()
        stopPeriodicLocationRequest()
        stopWorkoutTimer()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * Restore in-progress session from disk after process death.
     * @return true if an active session was restored and tracking resumed.
     */
    private fun restoreFromCheckpoint(): Boolean {
        val existing = sessionManager.getSession()
        if (existing.isTracking || existing.isPaused) {
            // Already live in this process (e.g. bound UI + sticky race)
            if (!isCurrentlyTracking && !existing.isPaused) {
                // Session flags say running but timers not started — re-arm
                resumeTrackingAfterRestore(existing)
            } else if (existing.isPaused) {
                ensureForegroundNotification()
            }
            return true
        }

        val checkpoint = activeWorkoutStore.load() ?: return false
        if (!checkpoint.isTracking && !checkpoint.isPaused) {
            activeWorkoutStore.clear()
            return false
        }

        selectedWorkoutType = checkpoint.resolvedWorkoutType()
        modeSelectionKey = checkpoint.modeSelection
        intervalSegmentsJson = checkpoint.intervalSegmentsJson
        intervalCursor = checkpoint.intervalCursor()
        lastLocationTime = checkpoint.lastLocationTime
        lastLocation = checkpoint.toSession().currentLocation

        val restoredSession = checkpoint.toSession().let { session ->
            // Recompute elapsed wall time after gap so the clock doesn't freeze at kill time
            if (session.isTracking && !session.isPaused && session.startTime > 0L) {
                val elapsed = (System.currentTimeMillis() - session.startTime - session.totalPauseDuration)
                    .coerceAtLeast(session.currentTime)
                session.copy(currentTime = elapsed)
            } else {
                session
            }
        }
        sessionManager.restoreSession(restoredSession, checkpoint.lastUpdateTime)
        resumeTrackingAfterRestore(restoredSession)
        android.util.Log.i(
            "WorkoutTrackingService",
            "Restored workout checkpoint: distance=${restoredSession.distance}, time=${restoredSession.currentTime}"
        )
        return true
    }

    private fun resumeTrackingAfterRestore(session: WorkoutSession) {
        ensureForegroundNotification()
        if (session.isPaused) {
            isCurrentlyTracking = false
            stopWorkoutTimer()
            stopPeriodicLocationRequest()
        } else {
            isCurrentlyTracking = true
            if (hasLocationPermission()) {
                startLocationUpdates()
                startPeriodicLocationRequest()
            }
            startWorkoutTimer()
        }
        notificationManager.updateNotification(sessionManager.getSession(), force = true)
        maybeSaveCheckpoint(sessionManager.getSession(), force = true)
    }

    private fun ensureForegroundNotification() {
        startForeground(NOTIFICATION_ID, notificationManager.buildNotification(sessionManager.getSession()))
    }

    private fun requestRestoreIfCheckpointExists() {
        val session = sessionManager.getSession()
        if (session.isTracking || session.isPaused) return
        val checkpoint = activeWorkoutStore.load() ?: return
        if (!checkpoint.isTracking && !checkpoint.isPaused) {
            activeWorkoutStore.clear()
            return
        }
        val intent = Intent(this, WorkoutTrackingService::class.java).apply {
            action = ACTION_RESTORE_WORKOUT
        }
        try {
            ContextCompat.startForegroundService(this, intent)
        } catch (e: Exception) {
            android.util.Log.e("WorkoutTrackingService", "Failed to start restore", e)
        }
    }

    private fun maybeSaveCheckpoint(session: WorkoutSession, force: Boolean = false) {
        if (!session.isTracking && !session.isPaused) return
        val now = System.currentTimeMillis()
        if (!force && now - lastCheckpointSaveAt < CHECKPOINT_SAVE_MIN_INTERVAL_MS) return
        lastCheckpointSaveAt = now
        activeWorkoutStore.save(
            ActiveWorkoutCheckpoint.fromSession(
                session = session,
                workoutType = selectedWorkoutType,
                modeSelection = modeSelectionKey,
                intervalSegmentsJson = intervalSegmentsJson,
                intervalCursor = intervalCursor,
                lastLocationTime = lastLocationTime,
                lastUpdateTime = sessionManager.getLastUpdateTime()
            )
        )
    }

    // ------------------------------------------------------------------
    // Location updates
    // ------------------------------------------------------------------

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
    }

    private fun startLocationUpdates() {
        val locationRequest = GpsConfig.createWorkoutLocationRequest()
        currentLocationRequest = locationRequest

        try {
            val callback = locationCallback ?: return
            fusedLocationClient?.requestLocationUpdates(
                locationRequest,
                callback,
                mainLooper
            )
            lastAppliedAdaptiveIntervalMs = GpsConfig.HIGH_ACCURACY_INTERVAL

            // Fetch last known location
            requestLastKnownLocation { location ->
                updateLocation(location)
            }

        } catch (e: SecurityException) {
            android.util.Log.e("WorkoutTrackingService", "Location permission denied", e)
            sessionManager.updateGpsStatus(GpsStatus.DENIED)
        } catch (e: Exception) {
            android.util.Log.e("WorkoutTrackingService", "GPS error: ${e.message}", e)
            sessionManager.updateGpsStatus(GpsStatus.LOST)
        }
    }

    private fun stopLocationUpdates() {
        locationCallback?.let { callback ->
            fusedLocationClient?.removeLocationUpdates(callback)
        }
    }

    /**
     * Fetches last known location only when FINE or COARSE permission is granted.
     * Satisfies MissingPermission lint and handles runtime revocation.
     */
    @SuppressLint("MissingPermission")
    private fun requestLastKnownLocation(onLocation: (Location) -> Unit) {
        val fineGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!fineGranted && !coarseGranted) {
            sessionManager.updateGpsStatus(GpsStatus.DENIED)
            return
        }
        try {
            fusedLocationClient?.lastLocation?.addOnSuccessListener { location ->
                location?.let(onLocation)
            }
        } catch (e: SecurityException) {
            android.util.Log.w("WorkoutTrackingService", "lastLocation denied", e)
            sessionManager.updateGpsStatus(GpsStatus.DENIED)
        }
    }

    // ------------------------------------------------------------------
    // Periodic location request (timeout guard)
    // ------------------------------------------------------------------

    private fun startPeriodicLocationRequest(intervalMs: Long = PERIODIC_LOCATION_REQUEST_INTERVAL_MS) {
        periodicLocationJob?.cancel()

        periodicLocationJob = serviceScope.launch {
            while (isActive && isCurrentlyTracking && !sessionManager.getSession().isPaused) {
                delay(intervalMs)
                if (!isActive) break
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastLocationTime > NO_LOCATION_UPDATE_TIMEOUT_MS) {
                    // Do NOT stamp lastLocationTime here — only Accepted / refreshGapClock
                    // updates should. Stale lastKnown would mask GpsStatus.LOST.
                    requestLastKnownLocation { location ->
                        updateLocation(location)
                    }
                }
                resolveGpsStatusDuringWorkout()
            }
        }
    }

    /**
     * Dynamically resolves GPS status during an active workout.
     *
     * Checks current location permission and GPS availability, updating the session's
     * gpsStatus so the UI can reflect mid-workout changes such as:
     *  - GPS becoming available after being unavailable
     *  - GPS becoming unavailable (e.g., entering a tunnel or building)
     *  - Location permissions being revoked while workout is running
     */
    private fun resolveGpsStatusDuringWorkout() {
        val hasFineLocation = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        val hasCoarseLocation = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

        if (!hasFineLocation && !hasCoarseLocation) {
            // Permissions revoked during workout
            sessionManager.updateGpsStatus(GpsStatus.DENIED)
            return
        }

        val currentStatus = sessionManager.getSession().gpsStatus

        // If we haven't received any location yet, keep searching status
        if (lastLocationTime == 0L) {
            if (currentStatus != GpsStatus.SEARCHING) {
                sessionManager.updateGpsStatus(GpsStatus.SEARCHING)
            }
            return
        }

        val elapsedSinceLastLocation = System.currentTimeMillis() - lastLocationTime

        when {
            elapsedSinceLastLocation > NO_LOCATION_UPDATE_TIMEOUT_MS * 3 -> {
                // No location for an extended period - GPS likely lost
                if (currentStatus != GpsStatus.LOST) {
                    android.util.Log.w("WorkoutTrackingService", "GPS signal lost during workout (${elapsedSinceLastLocation}ms since last fix)")
                    sessionManager.updateGpsStatus(GpsStatus.LOST)
                }
            }
            currentStatus == GpsStatus.LOST || currentStatus == GpsStatus.SEARCHING -> {
                // Recent accepted fix recovered the signal
                if (elapsedSinceLastLocation <= NO_LOCATION_UPDATE_TIMEOUT_MS) {
                    android.util.Log.i("WorkoutTrackingService", "GPS signal recovered during workout")
                    sessionManager.updateGpsStatus(GpsStatus.FOUND)
                }
            }
        }
    }

    private fun stopPeriodicLocationRequest() {
        periodicLocationJob?.cancel()
        periodicLocationJob = null
    }

    // ------------------------------------------------------------------
    // Adaptive GPS interval
    // ------------------------------------------------------------------

    private fun updateLocationRequestInterval(currentSpeed: Float) {
        if (!isCurrentlyTracking || sessionManager.getSession().isPaused) {
            return
        }

        val adaptiveInterval = GpsConfig.getAdaptiveInterval(currentSpeed)
        if (adaptiveInterval == lastAppliedAdaptiveIntervalMs) {
            return
        }

        try {
            locationCallback?.let { callback ->
                fusedLocationClient?.removeLocationUpdates(callback)
            }

            val newLocationRequest = GpsConfig.createAdaptiveLocationRequest(adaptiveInterval)
            currentLocationRequest = newLocationRequest

            val callback = locationCallback ?: return
            fusedLocationClient?.requestLocationUpdates(
                newLocationRequest,
                callback,
                mainLooper
            )

            lastAppliedAdaptiveIntervalMs = adaptiveInterval

        } catch (e: SecurityException) {
            android.util.Log.e("WorkoutTrackingService", "SecurityException updating location request: ${e.message}", e)
        } catch (e: Exception) {
            android.util.Log.e("WorkoutTrackingService", "Error updating location request: ${e.message}", e)
        }

        updatePeriodicTimerInterval()
    }

    private fun updatePeriodicTimerInterval() {
        // Keep the watchdog on the same cadence as workout GPS — don't slow it when nearly stopped
        if (isCurrentlyTracking && !sessionManager.getSession().isPaused) {
            startPeriodicLocationRequest(PERIODIC_LOCATION_REQUEST_INTERVAL_MS)
        }
    }

    // ------------------------------------------------------------------
    // Workout timer
    // ------------------------------------------------------------------

    private fun startWorkoutTimer() {
        stopWorkoutTimer()
        workoutTimerJob = serviceScope.launch {
            while (isActive && isCurrentlyTracking && !sessionManager.getSession().isPaused) {
                delay(WORKOUT_TIMER_INTERVAL_MS)
                sessionManager.tickElapsedTime()
                notificationManager.updateNotification(sessionManager.getSession())
            }
        }
    }

    private fun stopWorkoutTimer() {
        workoutTimerJob?.cancel()
        workoutTimerJob = null
    }

    // ------------------------------------------------------------------
    // Public API for UI (Binder compatibility)
    // ------------------------------------------------------------------

    private var sessionUpdateCallback: ((WorkoutSession) -> Unit)? = null

    fun setSessionUpdateCallback(callback: ((WorkoutSession) -> Unit)?) {
        sessionUpdateCallback = callback
    }

    fun getCurrentSession(): WorkoutSession = sessionManager.getSession()

    fun isTracking(): Boolean = sessionManager.isTracking()

    fun getSelectedWorkoutType(): WorkoutType = selectedWorkoutType

    fun getModeSelectionKey(): String? = modeSelectionKey

    fun getIntervalSegmentsJson(): String? = intervalSegmentsJson

    fun getIntervalCursor(): IntervalCursor? = intervalCursor

    fun updateUiMetadata(
        modeSelection: String?,
        intervalSegmentsJson: String?,
        intervalCursor: IntervalCursor?
    ) {
        if (modeSelection != null) modeSelectionKey = modeSelection
        if (intervalSegmentsJson != null) this.intervalSegmentsJson = intervalSegmentsJson
        if (intervalCursor != null) this.intervalCursor = intervalCursor
        // Cursor-only updates ride the next periodic session checkpoint; metadata changes save now.
        if (modeSelection != null || intervalSegmentsJson != null) {
            val session = sessionManager.getSession()
            if (session.isTracking || session.isPaused) {
                maybeSaveCheckpoint(session, force = true)
            }
        }
    }
}
