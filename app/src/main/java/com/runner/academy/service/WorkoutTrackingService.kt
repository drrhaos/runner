package com.runner.academy.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.runner.academy.data.GpsStatus
import com.runner.academy.data.WorkoutSession
import com.runner.academy.data.WorkoutType
import com.runner.academy.util.GpsConfig
import com.runner.academy.util.GpsDiagnostics.FixResult
import com.runner.academy.util.GpsDiagnostics.Event as DiagEvent
import com.runner.academy.util.GpsFilter
import com.runner.academy.util.GpsLocationClient
import com.runner.academy.util.IntervalSegmentsJson
import com.runner.academy.util.UserPreferences
import com.runner.academy.ui.tracking.VoiceFeedbackManager
import com.runner.academy.util.IntervalEngine
import com.runner.academy.data.SegmentGoalType
import com.runner.academy.data.WorkoutTemplateSegment
import com.runner.academy.data.localizedTitle
import com.runner.academy.util.FormatUtils
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationRequestCompat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Foreground service that coordinates workout tracking.
 *
 * Delegates to:
 *  - [GpsLocationProcessor] for GPS filtering and point processing
 *  - [WorkoutNotificationManager] for foreground notification lifecycle
 *  - [WorkoutSessionManager] for session state, metrics, and timer
 *  - [VoiceFeedbackManager] for distance / GPS / interval audio (works without UI)
 *
 * The service itself handles:
 *  - Android Service lifecycle (onCreate, onDestroy, onBind)
 *  - Binding with the platform GPS provider ([GpsLocationClient])
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
        /** Must exceed [GpsConfig.SCREEN_OFF_MAX_UPDATE_DELAY_MS]: batched fixes are not a GPS loss. */
        const val NO_LOCATION_UPDATE_TIMEOUT_SCREEN_OFF_MS = 30_000L
        const val PERIODIC_LOCATION_REQUEST_INTERVAL_MS = 2000L
        const val PERIODIC_LOCATION_REQUEST_SCREEN_OFF_MS = 10_000L
        const val WORKOUT_TIMER_INTERVAL_MS = 1000L
        private const val CHECKPOINT_SAVE_MIN_INTERVAL_MS = 15_000L
        /** Max age for a seeded / lastKnown fix used as the first track anchor. */
        const val PRE_START_LOCATION_MAX_AGE_MS = 10_000L
        /** The seed becomes the first track point, so it must be as good as a normal fix. */
        const val PRE_START_LOCATION_MAX_ACCURACY_M = 20f
        private const val FLUSH_REQUEST_CODE = 1
        private const val FLUSH_TIMEOUT_MS = 3_000L
    }

    // Extracted component instances
    private val gpsProcessor = GpsLocationProcessor()
    private lateinit var notificationManager: WorkoutNotificationManager
    private val sessionManager = WorkoutSessionManager()
    private lateinit var activeWorkoutStore: ActiveWorkoutStore
    private var voiceFeedback: VoiceFeedbackManager? = null
    private var lastAnnouncedGpsStatus: GpsStatus? = null
    private var serviceIntervalEngine: IntervalEngine? = null

    // Location provider bindings
    private var gpsClient: GpsLocationClient? = null
    private lateinit var diagnostics: GpsDiagnosticsRecorder
    private var currentLocationRequest: LocationRequestCompat? = null
    /** Runs once the provider reports [FLUSH_REQUEST_CODE] complete (see [flushThen]). */
    private var afterFlush: (() -> Unit)? = null

    /** Batched screen-off fixes arrive here one by one, oldest first. */
    private val locationListener = object : LocationListenerCompat {
        override fun onLocationChanged(location: Location) {
            updateLocation(location)
        }

        override fun onFlushComplete(requestCode: Int) {
            if (requestCode == FLUSH_REQUEST_CODE) mainHandler.post { runAfterFlush() }
        }
    }

    private fun runAfterFlush() {
        val continuation = afterFlush
        cancelPendingFlush()
        continuation?.invoke()
    }

    private fun cancelPendingFlush() {
        afterFlush = null
        mainHandler.removeCallbacks(flushTimeout)
    }

    /** Never leave a request change hanging if the provider does not report completion. */
    private val flushTimeout = Runnable { runAfterFlush() }

    // Tracking state
    private var isCurrentlyTracking = false
    private var lastLocation: Location? = null
    private var selectedWorkoutType: WorkoutType = WorkoutType.EASY_RUN
    private var modeSelectionKey: String? = null
    private var intervalSegmentsJson: String? = null
    private var intervalCursor: IntervalCursor? = null
    private var lastCheckpointSaveAt: Long = 0L

    // Coroutine management — Default for timers/IO; session mutations hop to Main
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.Default)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var periodicLocationJob: Job? = null
    private var lastLocationTime: Long = 0
    private var lastAppliedAdaptiveIntervalMs: Long = -1L
    private var lastAppliedScreenInteractive: Boolean? = null
    private var workoutTimerJob: Job? = null
    private var screenInteractive: Boolean = true
    private val turnDetector = GpsConfig.TurnDetector()
    private var turningDensifyActive: Boolean = false

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> applyScreenInteractive(false)
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> applyScreenInteractive(true)
            }
        }
    }

    // User preferences for calorie calculation / voice
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
        userPreferences = (applicationContext as com.runner.academy.RunnerApplication)
            .container.userPreferences
        activeWorkoutStore = ActiveWorkoutStore(this)
        gpsClient = GpsLocationClient(this)
        diagnostics = GpsDiagnosticsRecorder(
            this,
            (applicationContext as com.runner.academy.RunnerApplication).container.gpsDiagnosticsStore
        )
        screenInteractive = isDisplayInteractive()
        notificationManager.setScreenInteractive(screenInteractive)
        registerScreenStateReceiver()
        sessionManager.onSessionChanged = { session ->
            sessionUpdateCallback?.invoke(session)
            maybeSaveCheckpoint(session, force = session.isPaused)
            handleVoiceAndIntervals(session, announceIntervals = true)
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
        releaseVoice()
        serviceIntervalEngine = null
        unregisterScreenStateReceiver()
        super.onDestroy()
        stopWorkoutTimer()
        stopLocationUpdates()
        stopPeriodicLocationRequest()
        // Mid-workout destroy: the checkpoint resumes later and the recording continues
        diagnostics.release()
        serviceJob.cancel()
    }

    // ------------------------------------------------------------------
    // Location callback & processing
    // ------------------------------------------------------------------

    private fun updateLocation(location: Location) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { updateLocation(location) }
            return
        }

        if (isCurrentlyTracking && !sessionManager.getSession().isPaused) {
            val session = sessionManager.getSession()
            // Time gaps are detected from fix timestamps inside GpsFilter.isGapResume.
            // Wall-clock age must not be used: screen-off batches arrive ~20 s late by design.
            val resumeAfterGap = session.gpsStatus == GpsStatus.LOST

            val result = gpsProcessor.processLocation(
                location,
                lastLocation,
                selectedWorkoutType,
                session.trackPoints.toMutableList(),
                session.trackDataPoints.toMutableList(),
                session.rawTrackDataPoints.toMutableList(),
                resumeAfterGap = resumeAfterGap
            )

            diagnostics.recordFix(location, result.toFixResult())

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

                    val filtered = result.filteredLocation
                    val turning = screenInteractive && turnDetector.onFix(
                        bearingDeg = if (filtered.hasBearing()) filtered.bearing else null,
                        speedMps = if (filtered.hasSpeed()) filtered.speed else 0f
                    )
                    if (turning != turningDensifyActive || result.trackPoints.size % 10 == 0) {
                        turningDensifyActive = turning
                        updateLocationRequestInterval(sessionManager.getSession().currentSpeed)
                    }

                    lastLocation = filtered
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
            diagnostics.recordFix(location, FixResult.NOT_PROCESSED)
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

        lastAppliedAdaptiveIntervalMs = -1L
        lastAppliedScreenInteractive = null
        turnDetector.reset()
        turningDensifyActive = false
        isCurrentlyTracking = true
        // A fresh, accurate pre-start fix becomes the first track point (distance 0);
        // it is not kept as a hidden anchor, so track and distance stay consistent.
        val preStartSeed = lastLocation?.takeIf { isUsablePreStartLocation(it) }
        lastLocation = null
        lastLocationTime = 0L
        screenInteractive = isDisplayInteractive()
        notificationManager.setScreenInteractive(screenInteractive)

        val initialGpsStatus = if (hasPermission) GpsStatus.SEARCHING else GpsStatus.DENIED
        sessionManager.startNewSession(initialGpsStatus = initialGpsStatus)
        maybeSaveCheckpoint(sessionManager.getSession(), force = true)
        if (userPreferences.gpsDiagnostics) {
            diagnostics.start(sessionManager.getSession().startTime, resume = false)
        }

        if (hasPermission) {
            preStartSeed?.let { updateLocation(it) }
            startLocationUpdates()
            startPeriodicLocationRequest()
        }
        startWorkoutTimer()
        startForeground(NOTIFICATION_ID, notificationManager.buildNotification(sessionManager.getSession()))
        notificationManager.updateNotification(sessionManager.getSession(), force = true)
        prepareVoiceForWorkout()
        rebuildIntervalEngineFromMetadata()
    }

    fun pauseWorkout() {
        diagnostics.recordEvent(DiagEvent.PAUSE)
        isCurrentlyTracking = false
        sessionManager.pause()
        stopLocationUpdates()
        stopPeriodicLocationRequest()
        stopWorkoutTimer()
        notificationManager.updateNotification(sessionManager.getSession(), force = true)
        maybeSaveCheckpoint(sessionManager.getSession(), force = true)
    }

    fun resumeWorkout() {
        diagnostics.recordEvent(DiagEvent.RESUME)
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
        lastAppliedScreenInteractive = null
        turnDetector.reset()
        turningDensifyActive = false
        sessionManager.stop()
        activeWorkoutStore.clear()
        modeSelectionKey = null
        intervalSegmentsJson = null
        intervalCursor = null
        serviceIntervalEngine = null
        releaseVoice()
        stopLocationUpdates()
        stopPeriodicLocationRequest()
        stopWorkoutTimer()
        diagnostics.stop()
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
        rebuildIntervalEngineFromMetadata()
        prepareVoiceForWorkout()
        resumeTrackingAfterRestore(restoredSession)
        android.util.Log.i(
            "WorkoutTrackingService",
            "Restored workout checkpoint: distance=${restoredSession.distance}, time=${restoredSession.currentTime}"
        )
        return true
    }

    private fun resumeTrackingAfterRestore(session: WorkoutSession) {
        ensureForegroundNotification()
        if (userPreferences.gpsDiagnostics && session.startTime > 0L) {
            diagnostics.start(session.startTime, resume = true)
        }
        screenInteractive = isDisplayInteractive()
        notificationManager.setScreenInteractive(screenInteractive)
        if (session.isPaused) {
            isCurrentlyTracking = false
            stopWorkoutTimer()
            stopPeriodicLocationRequest()
            stopLocationUpdates()
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
        val snapshot = ActiveWorkoutCheckpoint.fromSession(
            session = session,
            workoutType = selectedWorkoutType,
            modeSelection = modeSelectionKey,
            intervalSegmentsJson = intervalSegmentsJson,
            intervalCursor = intervalCursor ?: serviceIntervalEngine?.snapshot(),
            lastLocationTime = lastLocationTime,
            lastUpdateTime = sessionManager.getLastUpdateTime()
        )
        serviceScope.launch(Dispatchers.IO) {
            activeWorkoutStore.save(snapshot)
        }
    }

    private fun prepareVoiceForWorkout() {
        if (!userPreferences.voiceFeedback) {
            releaseVoice()
            return
        }
        if (voiceFeedback == null) {
            voiceFeedback = VoiceFeedbackManager(applicationContext).also { it.initTTS() }
        }
        voiceFeedback?.resetMilestones()
        lastAnnouncedGpsStatus = null
    }

    private fun releaseVoice() {
        voiceFeedback?.destroy()
        voiceFeedback = null
        lastAnnouncedGpsStatus = null
    }

    private fun rebuildIntervalEngineFromMetadata() {
        val json = intervalSegmentsJson
        if (json.isNullOrBlank()) {
            serviceIntervalEngine = null
            return
        }
        val segments = IntervalSegmentsJson.parse(json)
        if (segments.isEmpty()) {
            serviceIntervalEngine = null
            return
        }
        val engine = IntervalEngine(segments)
        intervalCursor?.let { engine.restore(it) }
        serviceIntervalEngine = engine
    }

    private fun handleVoiceAndIntervals(session: WorkoutSession, announceIntervals: Boolean) {
        if (!(session.isTracking || session.isPaused)) return

        if (userPreferences.voiceFeedback) {
            if (voiceFeedback == null) prepareVoiceForWorkout()
            if (session.isTracking && !session.isPaused) {
                voiceFeedback?.notifyDistance(session)
            }
            voiceFeedback?.notifyGpsStatus(session.gpsStatus, lastAnnouncedGpsStatus)
            lastAnnouncedGpsStatus = session.gpsStatus
        }

        val engine = serviceIntervalEngine ?: run {
            rebuildIntervalEngineFromMetadata()
            serviceIntervalEngine
        } ?: return

        val state = engine.update(session.currentTime, session.distance * 1000f)
        intervalCursor = engine.snapshot()

        if (!announceIntervals || !userPreferences.voiceFeedback || session.isPaused) return
        val segment = state.segment
        if (engine.consumeSegmentAnnouncement(state) && segment != null) {
            voiceFeedback?.playIntervalBeep()
        }
        val remainingMs = engine.estimateRemainingMs(state, session.avgPace)
        val next = engine.peekNextSegment()
        if (next != null && engine.consumeUpcomingWarning(state, remainingMs)) {
            voiceFeedback?.announceIntervalUpcoming(
                title = next.localizedTitle(this),
                goalPart = formatSegmentVoiceGoal(next),
                pacePart = formatSegmentVoicePace(next)
            )
        }
    }

    private fun formatSegmentVoiceGoal(segment: WorkoutTemplateSegment): String? {
        return when (segment.goalType) {
            SegmentGoalType.DURATION -> segment.durationMs?.takeIf { it > 0 }?.let { ms ->
                getString(
                    com.runner.academy.R.string.voice_interval_goal_duration,
                    FormatUtils.formatTimeForTTS(ms, this)
                )
            }
            SegmentGoalType.DISTANCE -> segment.distanceMeters?.takeIf { it > 0f }?.let { meters ->
                getString(
                    com.runner.academy.R.string.voice_interval_goal_distance,
                    FormatUtils.formatDistanceMetersForTTS(meters, this)
                )
            }
        }
    }

    private fun formatSegmentVoicePace(segment: WorkoutTemplateSegment): String? {
        val pace = segment.targetPaceMinPerKm?.takeIf { it > 0f } ?: return null
        return getString(
            com.runner.academy.R.string.voice_interval_goal_pace,
            FormatUtils.formatPaceForTTS(pace, this)
        )
    }

    // ------------------------------------------------------------------
    // Location updates
    // ------------------------------------------------------------------

    /**
     * Seeds a pre-workout fix from the UI so Start does not begin from a cold / stale anchor.
     * Ignored while a workout is already running.
     */
    fun seedPreStartLocation(location: Location) {
        if (isCurrentlyTracking || sessionManager.getSession().isTracking) return
        if (!isUsablePreStartLocation(location)) return
        lastLocation = location
        lastLocationTime = System.currentTimeMillis()
    }

    private fun isUsablePreStartLocation(location: Location): Boolean {
        val ageMs = (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000L
        if (ageMs !in 0..PRE_START_LOCATION_MAX_AGE_MS) return false
        if (location.hasAccuracy() && location.accuracy > PRE_START_LOCATION_MAX_ACCURACY_M) {
            return false
        }
        return GpsFilter.isValidGpsLocation(location)
    }

    private fun hasLocationPermission(): Boolean = GpsLocationClient.hasPrecisePermission(this)

    private fun startLocationUpdates() {
        val locationRequest = GpsConfig.createWorkoutLocationRequest(screenInteractive)
        currentLocationRequest = locationRequest

        try {
            val client = gpsClient ?: return
            // Avoid duplicate registrations (resume / restore / profile switch races)
            cancelPendingFlush()
            client.removeUpdates(locationListener)
            client.requestUpdates(locationRequest, locationListener, mainLooper)
            lastAppliedAdaptiveIntervalMs = GpsConfig.getAdaptiveInterval(
                currentSpeed = 0f,
                screenInteractive = screenInteractive,
                turning = false
            )
            lastAppliedScreenInteractive = screenInteractive

            // Only use lastKnown when fresh — stale cache causes the first track point to jump
            requestLastKnownLocation { location ->
                if (isUsablePreStartLocation(location)) {
                    updateLocation(location)
                }
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
        cancelPendingFlush()
        gpsClient?.removeUpdates(locationListener)
    }

    /**
     * Forces delivery of fixes batched while the screen is off (they arrive via
     * [locationListener]), then runs [onFlushed]. Without batching support it runs at once.
     */
    private fun flushThen(onFlushed: () -> Unit = {}) {
        check(Looper.myLooper() == Looper.getMainLooper())
        val flushing = try {
            gpsClient?.flush(locationListener, FLUSH_REQUEST_CODE) == true
        } catch (e: Exception) {
            android.util.Log.w("WorkoutTrackingService", "GPS flush failed: ${e.message}")
            false
        }
        if (!flushing) {
            onFlushed()
            return
        }
        // A flush may already be pending (watchdog vs. request change): run both after it
        val pending = afterFlush
        if (pending == null) {
            afterFlush = onFlushed
            // Armed once per chain so overlapping flushes cannot postpone it forever
            mainHandler.postDelayed(flushTimeout, FLUSH_TIMEOUT_MS)
        } else {
            afterFlush = { pending(); onFlushed() }
        }
    }

    /**
     * Fetches the cached GPS fix only when precise location permission is granted.
     * Satisfies MissingPermission lint and handles runtime revocation.
     */
    @SuppressLint("MissingPermission")
    private fun requestLastKnownLocation(onLocation: (Location) -> Unit) {
        if (!hasLocationPermission()) {
            sessionManager.updateGpsStatus(GpsStatus.DENIED)
            return
        }
        try {
            gpsClient?.lastKnownLocation()?.let(onLocation)
        } catch (e: SecurityException) {
            android.util.Log.w("WorkoutTrackingService", "lastKnownLocation denied", e)
            sessionManager.updateGpsStatus(GpsStatus.DENIED)
        }
    }

    // ------------------------------------------------------------------
    // Periodic location request (timeout guard)
    // ------------------------------------------------------------------

    private fun startPeriodicLocationRequest(intervalMs: Long = defaultWatchdogIntervalMs()) {
        periodicLocationJob?.cancel()
        val lostTimeoutMs = locationLostTimeoutMs()

        periodicLocationJob = serviceScope.launch {
            while (isActive && isCurrentlyTracking && !sessionManager.getSession().isPaused) {
                delay(intervalMs)
                if (!isActive) break
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastLocationTime > lostTimeoutMs) {
                    // Do NOT stamp lastLocationTime here — only Accepted / refreshGapClock
                    // updates should. Stale lastKnown would mask GpsStatus.LOST.
                    if (!screenInteractive) {
                        // Fixes may still sit in the batch: pull them in order instead of
                        // injecting a newer lastKnown that would make the batch "too old".
                        mainHandler.post { flushThen() }
                    } else {
                        val ageCapMs = lostTimeoutMs
                        // Reads main-thread state (lastLocation, session): hop like the flush branch
                        mainHandler.post {
                            requestLastKnownLocation { location ->
                                val age = (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) /
                                    1_000_000L
                                val newerThanTrack = location.time > (lastLocation?.time ?: 0L)
                                if (age in 0..ageCapMs && newerThanTrack) {
                                    updateLocation(location)
                                }
                            }
                        }
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
        if (!hasLocationPermission()) {
            // Permissions revoked (or downgraded to approximate) during workout
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
        val lostTimeoutMs = locationLostTimeoutMs()

        when {
            elapsedSinceLastLocation > lostTimeoutMs * 3 -> {
                // No location for an extended period - GPS likely lost
                if (currentStatus != GpsStatus.LOST) {
                    android.util.Log.w("WorkoutTrackingService", "GPS signal lost during workout (${elapsedSinceLastLocation}ms since last fix)")
                    sessionManager.updateGpsStatus(GpsStatus.LOST)
                }
            }
            currentStatus == GpsStatus.LOST || currentStatus == GpsStatus.SEARCHING -> {
                // Recent accepted fix recovered the signal
                if (elapsedSinceLastLocation <= lostTimeoutMs) {
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

        val adaptiveInterval = GpsConfig.getAdaptiveInterval(
            currentSpeed = currentSpeed,
            screenInteractive = screenInteractive,
            turning = turningDensifyActive
        )
        if (adaptiveInterval == lastAppliedAdaptiveIntervalMs &&
            lastAppliedScreenInteractive == screenInteractive
        ) {
            return
        }

        try {
            val client = gpsClient ?: return
            val newLocationRequest = GpsConfig.createAdaptiveLocationRequest(
                intervalMs = adaptiveInterval,
                screenInteractive = screenInteractive
            )
            currentLocationRequest = newLocationRequest
            lastAppliedAdaptiveIntervalMs = adaptiveInterval
            lastAppliedScreenInteractive = screenInteractive

            // Deliver pending batched fixes first, then replace the request on the same
            // listener (no remove: a removed request may discard its undelivered batch).
            flushThen {
                if (!isCurrentlyTracking || sessionManager.getSession().isPaused) return@flushThen
                if (currentLocationRequest !== newLocationRequest) return@flushThen
                try {
                    client.requestUpdates(newLocationRequest, locationListener, mainLooper)
                } catch (e: SecurityException) {
                    android.util.Log.e("WorkoutTrackingService", "SecurityException updating location request: ${e.message}", e)
                    sessionManager.updateGpsStatus(GpsStatus.DENIED)
                }
            }

        } catch (e: SecurityException) {
            android.util.Log.e("WorkoutTrackingService", "SecurityException updating location request: ${e.message}", e)
        } catch (e: Exception) {
            android.util.Log.e("WorkoutTrackingService", "Error updating location request: ${e.message}", e)
        }

        updatePeriodicTimerInterval()
    }

    private fun updatePeriodicTimerInterval() {
        if (isCurrentlyTracking && !sessionManager.getSession().isPaused) {
            startPeriodicLocationRequest(defaultWatchdogIntervalMs())
        }
    }

    private fun defaultWatchdogIntervalMs(): Long =
        if (screenInteractive) PERIODIC_LOCATION_REQUEST_INTERVAL_MS
        else PERIODIC_LOCATION_REQUEST_SCREEN_OFF_MS

    private fun locationLostTimeoutMs(): Long =
        if (screenInteractive) NO_LOCATION_UPDATE_TIMEOUT_MS
        else NO_LOCATION_UPDATE_TIMEOUT_SCREEN_OFF_MS

    private fun isDisplayInteractive(): Boolean {
        val pm = getSystemService(POWER_SERVICE) as? PowerManager ?: return true
        return pm.isInteractive
    }

    private fun applyScreenInteractive(interactive: Boolean) {
        if (screenInteractive == interactive) return
        screenInteractive = interactive
        notificationManager.setScreenInteractive(interactive)
        diagnostics.recordEvent(if (interactive) DiagEvent.SCREEN_ON else DiagEvent.SCREEN_OFF)
        // Bearings from before the switch (or from a batch) are not comparable
        turnDetector.reset()
        turningDensifyActive = false
        if (isCurrentlyTracking && !sessionManager.getSession().isPaused) {
            lastAppliedScreenInteractive = null
            updateLocationRequestInterval(sessionManager.getSession().currentSpeed)
        }
    }

    private fun registerScreenStateReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(
            this,
            screenStateReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private fun unregisterScreenStateReceiver() {
        try {
            unregisterReceiver(screenStateReceiver)
        } catch (_: IllegalArgumentException) {
            // Already unregistered
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
                withContext(Dispatchers.Main) {
                    // Advance clock without fan-out to voice/checkpoint; UI + notification only
                    sessionManager.tickElapsedTime(broadcast = false)
                    val session = sessionManager.getSession()
                    sessionUpdateCallback?.invoke(session)
                    notificationManager.updateNotification(session)
                }
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
        val segmentsChanged = intervalSegmentsJson != null &&
            intervalSegmentsJson != this.intervalSegmentsJson
        if (intervalSegmentsJson != null) this.intervalSegmentsJson = intervalSegmentsJson
        if (intervalCursor != null) {
            this.intervalCursor = intervalCursor
            serviceIntervalEngine?.restore(intervalCursor)
        }
        if (segmentsChanged) {
            rebuildIntervalEngineFromMetadata()
        }
        // Cursor-only updates ride the next periodic session checkpoint; metadata changes save now.
        if (modeSelection != null || intervalSegmentsJson != null) {
            val session = sessionManager.getSession()
            if (session.isTracking || session.isPaused) {
                maybeSaveCheckpoint(session, force = true)
            }
        }
    }
}

/** The live filter's verdict as recorded in GPS diagnostics. */
private fun GpsLocationProcessor.ProcessResult?.toFixResult(): FixResult = when (this) {
    null -> FixResult.REJECTED
    is GpsLocationProcessor.ProcessResult.Accepted -> FixResult.ACCEPTED
    is GpsLocationProcessor.ProcessResult.Rejected ->
        if (refreshGapClock) FixResult.NEAR_DUPLICATE else FixResult.REJECTED
}
