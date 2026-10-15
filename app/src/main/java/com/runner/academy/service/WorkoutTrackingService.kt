package com.runner.academy.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.annotation.VisibleForTesting
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.runner.academy.data.GpsStatus
import com.runner.academy.data.WorkoutSession
import com.runner.academy.data.WorkoutType
import com.runner.academy.sensor.ForegroundTypes
import com.runner.academy.sensor.SensorDiagEvent
import com.runner.academy.sensor.SensorPermissionPolicy
import com.runner.academy.sensor.SensorType
import com.runner.academy.sensor.HeartRateDisplay
import com.runner.academy.ui.tracking.HeartRateVoiceTimer
import com.runner.academy.ui.tracking.HrAnnouncement
import com.runner.academy.util.GpsConfig
import com.runner.academy.util.GpsDiagnostics.FixResult
import com.runner.academy.util.GpsDiagnostics.Event as DiagEvent
import com.runner.academy.util.GpsFilter
import com.runner.academy.util.GpsLocationClient
import com.runner.academy.util.IntervalSegmentsJson
import com.runner.academy.util.StepDistanceEstimator
import com.runner.academy.util.StepTrackingAccess
import com.runner.academy.util.StrideLearner
import com.runner.academy.util.StrideModel
import com.runner.academy.util.UserPreferences
import com.runner.academy.util.IntervalEngine
import com.runner.academy.ui.tracking.AutoPauseAnnouncement
import com.runner.academy.ui.tracking.AutoPauseVoiceTransition
import com.runner.academy.ui.tracking.GpsAnnouncement
import com.runner.academy.ui.tracking.GpsVoiceTransition
import com.runner.academy.voice.AndroidVoiceOutput
import com.runner.academy.voice.CoachInput
import com.runner.academy.voice.StatusKind
import com.runner.academy.voice.VoiceCoach
import com.runner.academy.voice.VoiceLine
import com.runner.academy.voice.VoiceOutput
import com.runner.academy.voice.VoiceSettings
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationRequestCompat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
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
 *  - [VoiceCoach] on the 1 s timer for the summaries and the segments, and a [VoiceOutput] that
 *    says them and the GPS / sensor / auto-pause events (works without UI)
 *  - [StepTracker] for steps (distance over false-signal stretches) and [StrideLearner] to
 *    teach the stride model on good GPS stretches
 *  - [BarometerTracker] for the pressure altitude of each fix (elevation gain)
 *  - [HeartRateTracker] for the heart rate of each fix and the tile, under the sensor lease
 *    `"workout"` while a sensor is paired (kept over pauses)
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
        /** Screen off: the system may still deliver fixes late, do not flag GPS as lost early. */
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
        /**
         * Safety net if a release is ever missed; renewed by the workout timer long before it
         * expires, so a run of any length keeps the CPU awake.
         */
        private const val WAKE_LOCK_TIMEOUT_MS = 10 * 60_000L
        private const val WAKE_LOCK_RENEW_EVERY_TICKS = 60
        private const val WAKE_LOCK_TAG = "Runner:WorkoutTracking"
        /** Fixes this accurate (or better) may teach the stride model. */
        private const val STRIDE_LEARN_MAX_ACCURACY_M = 20f
        private const val RSSI_EVERY_MS = 30_000L
        private const val NANOS_PER_MS = 1_000_000L
        /** Diagnostics event: the system refused the connected-device service type. */
        const val FGS_NO_CONNECTED_DEVICE = "fgs_no_connected_device"
    }

    // Extracted component instances
    private val gpsProcessor = GpsLocationProcessor()
    private lateinit var notificationManager: WorkoutNotificationManager
    private val sessionManager = WorkoutSessionManager()
    private lateinit var activeWorkoutStore: ActiveWorkoutStore
    /** The voice while it is switched on; made by [voiceOutputFactory]. */
    private var voiceOutput: VoiceOutput? = null
    /** One per workout (a new one after a restore, see [prepareVoiceForWorkout]). */
    private var voiceCoach = VoiceCoach()
    private var lastAnnouncedGpsStatus: GpsStatus? = null
    private var serviceIntervalEngine: IntervalEngine? = null
    /** The current-pace tile's pace; one per run on its frozen stride (see [tickLivePace]). */
    private var livePaceTracker = LivePaceTracker(stride = null)

    // Steps: tracker while allowed, the model that learns this run, and the learner feeding it
    private var stepTracker: StepTracker? = null
    private var learningStrideModel: StrideModel? = null
    private var strideLearner: StrideLearner? = null
    private var strideSamplesAtStart = 0
    /** Pressure altitude for each fix's `baro_m`; listens from the start to the stop of a run. */
    private var barometerTracker: BarometerTracker? = null

    /**
     * The heart-rate sensor of the app; tests set it before `onCreate`. The service knows no
     * Bluetooth: it holds a lease and reads samples.
     */
    @VisibleForTesting
    internal var heartRateSource: HeartRateSource? = null
    private lateinit var heartRate: HeartRateSource

    /** Where samples are collected; tests use an unconfined one to see them at once. */
    @VisibleForTesting
    internal var heartRateDispatcher: CoroutineDispatcher = Dispatchers.Default

    /** How often the sensor's signal strength goes to the diagnostics log. */
    @VisibleForTesting
    internal var rssiEveryMs: Long = RSSI_EVERY_MS

    /** `startForeground` with explicit types; tests replace it to play a refusal. */
    @VisibleForTesting
    internal var foregroundStarter: (Notification, Int) -> Unit = { notification, types ->
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, types)
    }

    /** The heart rate of each fix's `hr` and the tile; runs while a paired sensor is in the workout. */
    private var heartRateTracker: HeartRateTracker? = null
    /** The 1 s tile tick, the sensor's log events and the RSSI reads; lives with the tracker. */
    private var heartRateJob: Job? = null
    private var heartRateVoice: HeartRateVoiceTimer? = null
    /**
     * The system refused the connected-device service type (34+) and took location only: this
     * workout goes without the sensor (no lease, no points' `hr`). The user is not told: the
     * tile shows the sensor lost ([HeartRateDisplay.UNAVAILABLE]), no voice (never a valid
     * sample), the log has `fgs_no_connected_device`.
     */
    private var heartRateRefused = false

    /** The clock of the heart rate (samples, tile, voice); tests set their own. */
    @VisibleForTesting
    internal var heartRateClockNanos: () -> Long = SystemClock::elapsedRealtimeNanos

    /** Makes the voice when it is switched on; tests hear the lines through a fake. */
    @VisibleForTesting
    internal var voiceOutputFactory: (Context) -> VoiceOutput = ::AndroidVoiceOutput

    /**
     * The service's wall clock: the session's start, pause, resume, restore and timer tick
     * (session time, segments, summaries), the fixes' receipt times and the GPS watchdog, the
     * checkpoint throttle; tests set their own.
     */
    @VisibleForTesting
    internal var sessionClockMs: () -> Long = System::currentTimeMillis

    /** The service is in the foreground for a workout (a new pairing restarts it with new types). */
    private var foregroundStarted = false
    private val unreliableLatch = UnreliableSignalLatch()
    /** Fed every processed fix; ticked on the workout timer while auto-pause applies. */
    private val autoPause = AutoPauseController()

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
    // Written on the main thread, read by the watchdog coroutine
    /** Wall time of the last good (accepted / near-duplicate) fix; 0 before the first. */
    @Volatile private var lastLocationTime: Long = 0
    /** Wall time of the last fix fed to the filter, false ones included; 0 before the first. */
    @Volatile private var lastAnyFixTime: Long = 0
    /** Fix time of the newest fix fed to the filter (a stale lastKnown must not repeat it). */
    @Volatile private var lastProcessedFixTimeMs: Long = 0
    private var lastAppliedAdaptiveIntervalMs: Long = -1L
    private var lastAppliedScreenInteractive: Boolean? = null
    private var workoutTimerJob: Job? = null

    /**
     * Held while the workout timer runs. The location foreground service only keeps the process
     * alive, not the CPU: with the screen off a test phone let the CPU sleep and the GPS
     * stopped delivering fixes for minutes.
     */
    private val wakeLock: PowerManager.WakeLock? by lazy {
        (getSystemService(POWER_SERVICE) as? PowerManager)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            ?.apply { setReferenceCounted(false) }
    }
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
        stepTracker = StepTracker(this)
        barometerTracker = BarometerTracker(this)
        diagnostics = GpsDiagnosticsRecorder(
            this,
            (applicationContext as com.runner.academy.RunnerApplication).container.gpsDiagnosticsStore
        )
        heartRate = heartRateSource ?: run {
            val container = (applicationContext as com.runner.academy.RunnerApplication).container
            HeartRateSource(container.heartRateSensor, container.pairedSensors) {
                SensorPermissionPolicy.connectPermissions(Build.VERSION.SDK_INT).all {
                    ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
                }
            }
        }
        watchPairing()
        screenInteractive = isDisplayInteractive()
        notificationManager.setScreenInteractive(screenInteractive)
        registerScreenStateReceiver()
        sessionManager.onSessionChanged = { session ->
            sessionUpdateCallback?.invoke(session)
            maybeSaveCheckpoint(session, force = session.isPaused)
            announceGpsStatus(session)
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
        // Mid-workout destroy: the checkpoint resumes later (steps continue from it); what the
        // run taught the stride model so far is kept, a restore reloads it
        stopSteps(saveLearned = true)
        barometerTracker?.stop()
        stopHeartRate()
        diagnostics.release()
        serviceJob.cancel()
    }

    // ------------------------------------------------------------------
    // Location callback & processing
    // ------------------------------------------------------------------

    @VisibleForTesting
    internal fun updateLocation(location: Location) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { updateLocation(location) }
            return
        }

        if (isCurrentlyTracking && !sessionManager.getSession().isPaused) {
            val session = sessionManager.getSession()
            // Time gaps are detected from fix timestamps inside the processor's TrackFilter,
            // counted from the last valid fix (near-duplicates included).
            // Wall-clock age must not be used: flushed or delayed fixes arrive late.
            // UNRELIABLE does not force a resume: false fixes keep the filter's own clock, which
            // turns a long dropped stretch into a gap resume (bridge) by itself.
            val resumeAfterGap = session.gpsStatus == GpsStatus.LOST

            // Steps at the fix's own time: batched screen-off fixes arrive late
            val tracker = stepTracker?.takeIf { it.isRunning }
            val steps = tracker?.stepsAt(location.elapsedRealtimeNanos)
            val cadence = tracker?.cadence
            // The pressure altitude of the fix's own moment too
            val baroM = barometerTracker?.altitudeAt(location.elapsedRealtimeNanos)
            // And the heart rate (a fix without its own monotonic time is taken as now)
            val hr = heartRateTracker?.bpmAt(
                location.elapsedRealtimeNanos.takeIf { it > 0L } ?: SystemClock.elapsedRealtimeNanos()
            )

            val result = gpsProcessor.processLocation(
                location,
                session.trackPoints.toMutableList(),
                session.trackDataPoints.toMutableList(),
                session.rawTrackDataPoints.toMutableList(),
                resumeAfterGap = resumeAfterGap,
                steps = steps,
                cadence = cadence,
                baroM = baroM,
                heartRate = hr
            )
            lastAnyFixTime = sessionClockMs()
            lastProcessedFixTimeMs = maxOf(lastProcessedFixTimeMs, location.time)
            // Before the session update below, so a stop right after a closing fix saves no tail
            sessionManager.setOpenStepMeters(gpsProcessor.pendingStepMeters)

            diagnostics.recordFix(
                location,
                result.toFixResult(),
                reason = (result as? GpsLocationProcessor.ProcessResult.Rejected)?.reason?.name,
                steps = steps,
                cadence = cadence,
                heartRate = hr
            )

            val good = when (result) {
                is GpsLocationProcessor.ProcessResult.Accepted -> true
                is GpsLocationProcessor.ProcessResult.Rejected -> result.refreshGapClock
            }
            val unreliable = unreliableLatch.onFix(gpsProcessor.inFalseSignal, good, location.time)
            autoPause.onFix(
                atMono = monoTimeOf(location),
                speedMps = if (location.hasSpeed()) location.speed else null,
                usable = good && !unreliable
            )
            // Leaving UNRELIABLE needs a status change even on a fix that keeps the old one
            val statusOverride = when {
                unreliable -> GpsStatus.UNRELIABLE
                session.gpsStatus == GpsStatus.UNRELIABLE && good -> GpsStatus.FOUND
                else -> null
            }

            when (result) {
                is GpsLocationProcessor.ProcessResult.Accepted -> {
                    sessionManager.updateMetricsFromLocation(
                        segmentDistanceMeters = result.distanceDeltaMeters,
                        trackPoints = result.trackPoints,
                        trackDataPoints = result.trackDataPoints,
                        rawTrackDataPoints = result.rawTrackDataPoints,
                        userWeightKg = userPreferences.userWeight,
                        currentLocation = result.filteredLocation,
                        gpsStatus = if (unreliable) GpsStatus.UNRELIABLE else GpsStatus.FOUND
                    )
                    strideLearner?.onAccepted(
                        segmentMeters = result.segmentDistanceMeters,
                        afterGap = result.afterGap,
                        bridged = result.bridgeMeters != null,
                        steps = steps,
                        timeMs = location.time,
                        reliable = !unreliable &&
                            (!location.hasAccuracy() || location.accuracy <= STRIDE_LEARN_MAX_ACCURACY_M)
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
                    lastLocationTime = sessionClockMs()
                }

                is GpsLocationProcessor.ProcessResult.Rejected -> {
                    if (result.refreshGapClock) {
                        lastLocationTime = sessionClockMs()
                        // Standing still: the stride sample would mix in time without steps
                        strideLearner?.reset()
                        // Near-duplicate fix: move map tip / icon without committing a track point
                        sessionManager.updateLocationOnly(
                            currentLocation = location,
                            rawTrackDataPoints = result.rawTrackDataPoints,
                            gpsStatus = statusOverride
                        )
                    } else if (result.retractedStart) {
                        // The start fix was a false signal: the track restarts at the next good fix
                        lastLocation = null
                        sessionManager.updateLocationOnly(
                            currentLocation = sessionManager.getSession().currentLocation ?: location,
                            rawTrackDataPoints = result.rawTrackDataPoints,
                            trackPoints = result.trackPoints,
                            trackDataPoints = result.trackDataPoints,
                            addedDistanceMeters = result.distanceDeltaMeters,
                            userWeightKg = userPreferences.userWeight,
                            gpsStatus = statusOverride
                        )
                    } else {
                        // Outlier: keep tip on last accepted fix so the line does not jump.
                        // During a false-signal episode the distance grows by steps.
                        sessionManager.updateLocationOnly(
                            currentLocation = lastLocation ?: location,
                            rawTrackDataPoints = result.rawTrackDataPoints,
                            addedDistanceMeters = result.distanceDeltaMeters,
                            userWeightKg = userPreferences.userWeight,
                            gpsStatus = statusOverride
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
        lastAnyFixTime = 0L
        lastProcessedFixTimeMs = 0L
        unreliableLatch.reset()
        autoPause.reset()
        val runStride = startSteps(initialSteps = 0, frozenState = null)
        livePaceTracker = LivePaceTracker(runStride?.estimator)
        // No barometer: false, the points carry no baro_m and the elevation stays GPS
        barometerTracker?.start()
        gpsProcessor.reset(workoutType = selectedWorkoutType, stepDistance = runStride?.estimator)
        screenInteractive = isDisplayInteractive()
        notificationManager.setScreenInteractive(screenInteractive)

        // Before the session is published: the screen hands its pre-start lease over when it
        // sees the workout running, and the connection must not drop in between
        heartRateRefused = false
        syncHeartRate()

        val initialGpsStatus = if (hasPermission) GpsStatus.SEARCHING else GpsStatus.DENIED
        sessionManager.startNewSession(
            initialGpsStatus = initialGpsStatus,
            strideModelState = runStride?.state,
            now = sessionClockMs()
        )
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
        startForegroundTyped(notificationManager.buildNotification(sessionManager.getSession()))
        foregroundStarted = true
        notificationManager.cancelInterruptedNotification()
        notificationManager.updateNotification(sessionManager.getSession(), force = true)
        prepareVoiceForWorkout()
        rebuildIntervalEngineFromMetadata()
    }

    fun pauseWorkout() {
        diagnostics.recordEvent(DiagEvent.PAUSE)
        isCurrentlyTracking = false
        stepTracker?.pause()
        strideLearner?.reset()
        sessionManager.pause(sessionClockMs())
        stopLocationUpdates()
        stopPeriodicLocationRequest()
        stopWorkoutTimer()
        notificationManager.updateNotification(sessionManager.getSession(), force = true)
        maybeSaveCheckpoint(sessionManager.getSession(), force = true)
    }

    fun resumeWorkout() {
        diagnostics.recordEvent(DiagEvent.RESUME)
        isCurrentlyTracking = true
        stepTracker?.resume()
        // Kept listening over the pause; re-armed if an abandoned restore stopped it
        ensureBarometer()
        gpsProcessor.onResume()
        // A new auto-pause needs a fresh 10 s of standing after the resume
        autoPause.reset()
        // The moving time stood still over the pause: the window starts where it stopped
        livePaceTracker.restart(atMovingMs = sessionManager.getSession().movingTime)
        sessionManager.resume(sessionClockMs())
        // Grace after the pause: no fixes were processed, so the watchdog must not flag the
        // whole pause as a GPS loss before the first fix arrives
        if (lastLocationTime != 0L) lastLocationTime = sessionClockMs()
        if (lastAnyFixTime != 0L) lastAnyFixTime = sessionClockMs()
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
        stopSteps(saveLearned = true)
        barometerTracker?.stop()
        stopHeartRate()
        foregroundStarted = false
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
     * @return true if an active session was restored and tracking resumed; false also when the
     *   system refused the foreground start (the checkpoint is kept, see [abandonRestore]).
     */
    private fun restoreFromCheckpoint(): Boolean {
        val existing = sessionManager.getSession()
        if (existing.isTracking || existing.isPaused) {
            // Already live in this process (e.g. bound UI + sticky race)
            if (!isCurrentlyTracking && !existing.isPaused) {
                // Session flags say running but timers not started — re-arm
                resumeStepsAfterAbandon(existing)
                syncHeartRate()
                if (!resumeTrackingAfterRestore(existing)) return false
                ensureBarometer()
                return true
            } else if (existing.isPaused) {
                syncHeartRate()
                return ensureForegroundNotification()
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
        lastAnyFixTime = checkpoint.lastLocationTime
        lastProcessedFixTimeMs = checkpoint.rawTrackDataPoints.lastOrNull()?.timestamp ?: 0L
        lastLocation = checkpoint.toSession().currentLocation
        unreliableLatch.reset()
        // Steps continue from the checkpoint; bridges keep the run's frozen stride
        val savedSteps = maxOf(checkpoint.steps ?: 0, checkpoint.rawTrackDataPoints.lastOrNull()?.steps ?: 0)
        // Without steps now (setting or permission changed) the points before keep theirs: the
        // run's stride still bridges those, so live and saved stay equal
        val runStride = startSteps(initialSteps = savedSteps, frozenState = checkpoint.strideModelState)
            ?: checkpoint.strideModelState?.let { state ->
                RunStride(StrideModel.frozenEstimatorOf(state, userPreferences.userHeight), state)
            }
        // Not checkpointed: the window starts over
        livePaceTracker = LivePaceTracker(runStride?.estimator)
        // The gap clock is not checkpointed: it falls back to the anchor's own time
        gpsProcessor.reset(
            workoutType = selectedWorkoutType,
            anchor = lastLocation,
            anchorSteps = checkpoint.trackDataPoints.lastOrNull()?.steps,
            pendingMeters = checkpoint.pendingStepMeters,
            stepDistance = runStride?.estimator
        )

        // Elapsed recomputed after the gap so the clock doesn't freeze at kill time
        val restoredSession = checkpoint.toRestoredSession(sessionClockMs())
            .copy(strideModelState = runStride?.state)
        // No fix time before the restore counts (the anchor may be from before the kill)
        livePaceTracker.restart(atMovingMs = restoredSession.movingTime)
        // Afresh, before the session is published (see startWorkout); the machine starts with a
        // direct connection and the first one is silent. A refused restore stops it again.
        heartRateRefused = false
        syncHeartRate()
        sessionManager.restoreSession(restoredSession, checkpoint.lastUpdateTime)
        sessionManager.setOpenStepMeters(gpsProcessor.pendingStepMeters)
        // An open auto-pause stays open: the detector waits for fresh steps or speed, and the
        // resume that ends it is not announced
        autoPause.reset(autoPaused = restoredSession.autoPaused, restored = true)
        rebuildIntervalEngineFromMetadata()
        prepareVoiceForWorkout(restored = sessionManager.getSession())
        if (!resumeTrackingAfterRestore(restoredSession)) return false
        // Only once the restore holds (a refused one never starts it). Afresh: no calibration
        // to carry, the pressure altitudes go on from the same scale
        barometerTracker?.start()
        android.util.Log.i(
            "WorkoutTrackingService",
            "Restored workout checkpoint: distance=${restoredSession.distance}, time=${restoredSession.currentTime}"
        )
        return true
    }

    /** @return false if the foreground start was refused and the restore abandoned. */
    private fun resumeTrackingAfterRestore(session: WorkoutSession): Boolean {
        // Before the foreground start, so a refused restore is recorded too
        if (userPreferences.gpsDiagnostics && session.startTime > 0L) {
            diagnostics.start(session.startTime, resume = true)
        }
        if (!ensureForegroundNotification()) return false
        screenInteractive = isDisplayInteractive()
        notificationManager.setScreenInteractive(screenInteractive)
        if (session.isPaused) {
            isCurrentlyTracking = false
            stepTracker?.pause()
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
        return true
    }

    /**
     * Restores run from the background (sticky restart), where the system may refuse the
     * foreground start; without this the service crashed and the track silently stopped.
     * @return false if refused — the restore is then abandoned, see [abandonRestore].
     */
    private fun ensureForegroundNotification(): Boolean {
        // Built outside: only the start itself may be refused
        val notification = notificationManager.buildNotification(sessionManager.getSession())
        val refusal = tryStartForeground { startForegroundTyped(notification) }
        if (refusal != null) {
            abandonRestore(refusal)
            return false
        }
        foregroundStarted = true
        notificationManager.cancelInterruptedNotification()
        return true
    }

    /**
     * With explicit types ([ForegroundTypes]): the plain `startForeground` takes every type in
     * the manifest, and `connectedDevice` there would throw on 34+ without `BLUETOOTH_CONNECT`.
     * Location always; connected device with a paired sensor in the workout and the permission.
     * A [SecurityException] with connected device is retried as location only. Only when that
     * one succeeds was it the connected-device type: this workout goes on without the sensor
     * (the tile shows it lost, the user is not told, the log gets `fgs_no_connected_device`).
     * A refusal of the location-only start propagates to the caller as before
     * ([tryStartForeground] / [abandonRestore], with its own log event).
     */
    private fun startForegroundTyped(notification: Notification) {
        val locationOnly = ForegroundTypes.of(Build.VERSION.SDK_INT, sensorPaired = false, connectGranted = false)
        val types = ForegroundTypes.of(
            Build.VERSION.SDK_INT,
            sensorPaired = heartRate.isPaired && !heartRateRefused,
            connectGranted = heartRate.connectGranted()
        )
        var refusedWithSensor: SecurityException? = null
        if (types != locationOnly) {
            try {
                foregroundStarter(notification, types)
                return
            } catch (e: SecurityException) {
                refusedWithSensor = e
            }
        }
        foregroundStarter(notification, locationOnly)
        if (refusedWithSensor != null) {
            android.util.Log.w("WorkoutTrackingService", "Connected-device service type refused, no heart rate", refusedWithSensor)
            heartRateRefused = true
            diagnostics.recordSensor(FGS_NO_CONNECTED_DEVICE)
            syncHeartRate()
        }
    }

    // ------------------------------------------------------------------
    // Heart rate
    // ------------------------------------------------------------------

    /**
     * The tracker runs while a workout has a paired sensor the system did not refuse. A refused
     * one stays on the tile as lost ([HeartRateDisplay.UNAVAILABLE]): never valid, so never
     * announced ([HeartRateVoiceTimer]).
     */
    private fun syncHeartRate() {
        when {
            !heartRate.isPaired -> stopHeartRate()
            heartRateRefused -> stopHeartRate(show = HeartRateDisplay.UNAVAILABLE)
            else -> startHeartRate()
        }
    }

    private fun startHeartRate() {
        if (heartRateTracker?.isRunning == true) return
        val tracker = heartRateTracker
            ?: HeartRateTracker(
                heartRate.sensor,
                clockNanos = { heartRateClockNanos() },
                scope = CoroutineScope(serviceJob + heartRateDispatcher)
            ).also { heartRateTracker = it }
        tracker.start()
        heartRateVoice = HeartRateVoiceTimer()
        val sensor = heartRate.sensor
        heartRateJob = serviceScope.launch(heartRateDispatcher) {
            launch {
                // Recorded on the main thread, like every other record: never after onDestroy
                // released the recorder (its executor would reject the write)
                sensor.diagnostics.collect { event -> withContext(Dispatchers.Main) { recordSensorEvent(event) } }
            }
            launch {
                // Only for the log: no radio work without the diagnostics recording
                while (isActive) {
                    delay(rssiEveryMs)
                    if (diagnostics.recording) sensor.readRssi()
                }
            }
            while (isActive) {
                withContext(Dispatchers.Main) { tickHeartRate() }
                delay(WORKOUT_TIMER_INTERVAL_MS)
            }
        }
    }

    private fun recordSensorEvent(event: SensorDiagEvent) {
        if (heartRateJob == null) return
        when (event.kind) {
            SensorDiagEvent.Kind.DISCONNECTED, SensorDiagEvent.Kind.GATT_ERROR ->
                diagnostics.recordSensor(event.kind.wireName, status = event.value)
            else -> diagnostics.recordSensor(event.kind.wireName, value = event.value)
        }
    }

    /** Gives the lease back; the tile shows [show] (null: no sensor). */
    private fun stopHeartRate(show: HeartRateDisplay? = null) {
        heartRateJob?.cancel()
        heartRateJob = null
        heartRateTracker?.stop()
        heartRateVoice = null
        if (sessionManager.setHeartRate(show)) sessionUpdateCallback?.invoke(sessionManager.getSession())
    }

    /**
     * Once a second for the whole workout, pauses included (the timer stops on a pause, the
     * tile shows the recovery heart rate): the tile, then the voice, which counts from the last
     * valid sample ([HeartRateVoiceTimer]).
     */
    @VisibleForTesting
    internal fun tickHeartRate() {
        val tracker = heartRateTracker?.takeIf { it.isRunning } ?: return
        val now = heartRateClockNanos()
        val display = tracker.display(now)
        val session = sessionManager.getSession()
        if (!(session.isTracking || session.isPaused)) return
        if (sessionManager.setHeartRate(display)) sessionUpdateCallback?.invoke(sessionManager.getSession())
        val announcement = heartRateVoice?.onTick(
            lastValidAtMs = tracker.lastValidNanos?.let { it / NANOS_PER_MS },
            nowMs = now / NANOS_PER_MS,
            manualPause = session.isPaused
        ) ?: return
        sayStatus(
            when (announcement) {
                HrAnnouncement.LOST -> StatusKind.HR_LOST
                HrAnnouncement.CONNECTED -> StatusKind.HR_CONNECTED
            }
        )
    }

    /**
     * A sensor paired, replaced or forgotten mid-workout: the foreground service gets the new
     * types (connected device needs one on 34+) and the tracker starts or stops. A new device
     * is picked up silently: the sensor follows the address itself.
     */
    private fun watchPairing() {
        serviceScope.launch {
            heartRate.paired
                .map { it[SensorType.HEART_RATE]?.address }
                .distinctUntilChanged()
                .drop(1)
                .collect { withContext(Dispatchers.Main) { onPairingChanged() } }
        }
    }

    private fun onPairingChanged() {
        val session = sessionManager.getSession()
        if (!foregroundStarted || !(session.isTracking || session.isPaused)) return
        val notification = notificationManager.buildNotification(session)
        // Paired from the Devices screen, so the app is in front; a refusal keeps the old types
        tryStartForeground { startForegroundTyped(notification) }?.let {
            android.util.Log.w("WorkoutTrackingService", "Foreground restart for a new pairing refused", it)
        }
        syncHeartRate()
    }

    /**
     * Stops tracking without a foreground service but keeps [activeWorkoutStore]: the user is
     * asked to open the app, and binding from the UI restores the workout from the foreground
     * (see [onBind]). The caller stops the service.
     */
    private fun abandonRestore(refusal: RuntimeException) {
        android.util.Log.e("WorkoutTrackingService", "Foreground start refused, workout restore abandoned", refusal)
        isCurrentlyTracking = false
        stopWorkoutTimer()
        stopLocationUpdates()
        stopPeriodicLocationRequest()
        releaseVoice()
        maybeSaveCheckpoint(sessionManager.getSession(), force = true)
        // The checkpoint keeps the steps; the next restore continues from them
        stopSteps(saveLearned = false)
        barometerTracker?.stop()
        stopHeartRate()
        foregroundStarted = false
        diagnostics.stop(DiagEvent.RESTORE_FAILED)
        notificationManager.showInterruptedNotification()
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
        val now = sessionClockMs()
        if (!force && now - lastCheckpointSaveAt < CHECKPOINT_SAVE_MIN_INTERVAL_MS) return
        lastCheckpointSaveAt = now
        val snapshot = ActiveWorkoutCheckpoint.fromSession(
            session = session,
            workoutType = selectedWorkoutType,
            modeSelection = modeSelectionKey,
            intervalSegmentsJson = intervalSegmentsJson,
            intervalCursor = intervalCursor ?: serviceIntervalEngine?.snapshot(),
            lastLocationTime = lastLocationTime,
            lastUpdateTime = sessionManager.getLastUpdateTime(),
            steps = stepTracker?.takeIf { it.isRunning }?.steps,
            pendingStepMeters = gpsProcessor.pendingStepMeters
        )
        serviceScope.launch(Dispatchers.IO) {
            activeWorkoutStore.save(snapshot)
        }
    }

    /** The run's frozen stride for bridges, and its serialized state for the save path. */
    private class RunStride(val estimator: StepDistanceEstimator, val state: String)

    /**
     * Starts the step tracker when steps are allowed and available, and the stride model that
     * learns this run. [frozenState] is a restored run's bridge stride (null: a frozen copy of
     * the learned model). Returns null without steps: bridges are straight lines only.
     */
    private fun startSteps(initialSteps: Int, frozenState: String?): RunStride? {
        stopSteps(saveLearned = false)
        val tracker = stepTracker ?: return null
        if (!StepTrackingAccess.isStepTrackingAllowed(this) || !tracker.start(initialSteps)) return null
        // Loaded once per run (start or restore); the frozen copy keeps live and saved bridges equal
        val model = userPreferences.loadStrideModel()
        learningStrideModel = model
        strideSamplesAtStart = model.sampleCount
        // Saved on every accepted sample (cheap): a process death keeps what was learned
        strideLearner = StrideLearner(model) { learned -> userPreferences.saveStrideModel(learned) }
        // A restored run keeps the exact stride it froze, an untrained prior included
        val frozen = frozenState?.let { StrideModel.deserializeExact(it) } ?: model.copy()
        return RunStride(frozen.frozenEstimator(), frozen.serialize())
    }

    /**
     * Re-arm in the same instance after [abandonRestore], which stopped the steps: the filter
     * (anchor, episode, run stride) is intact, so only counting and learning restart.
     */
    private fun resumeStepsAfterAbandon(session: WorkoutSession) {
        if (stepTracker?.isRunning == true) return
        val savedSteps = maxOf(
            activeWorkoutStore.load()?.steps ?: 0,
            session.rawTrackDataPoints.lastOrNull()?.steps ?: 0
        )
        startSteps(initialSteps = savedSteps, frozenState = session.strideModelState)
    }

    /** Starts the barometer unless it already listens (a no-op without one). */
    private fun ensureBarometer() {
        val tracker = barometerTracker ?: return
        if (!tracker.isRunning) tracker.start()
    }

    /** Stops counting steps; with [saveLearned] the model keeps what this run taught it. */
    private fun stopSteps(saveLearned: Boolean) {
        stepTracker?.stop()
        val model = learningStrideModel
        if (saveLearned && model != null && model.sampleCount > strideSamplesAtStart) {
            userPreferences.saveStrideModel(model)
        }
        learningStrideModel = null
        strideLearner = null
    }
    /**
     * A new coach for a new workout or a restored one ([restored]: its session). The coach is
     * not checkpointed: a restored one resumes the summaries from the restored position, so
     * nothing is said for the distance already run; the beep and the warning are kept from
     * repeating by the checkpointed cursor.
     */
    private fun prepareVoiceForWorkout(restored: WorkoutSession? = null) {
        voiceCoach = VoiceCoach()
        restored?.let { voiceCoach.resume(it.movingTime, it.distance * 1000f) }
        lastAnnouncedGpsStatus = null
        voiceOutputFor(userPreferences.voiceSettings())
    }

    /** The voice when it is switched on (made on first use), released when it is off. */
    private fun voiceOutputFor(settings: VoiceSettings): VoiceOutput? {
        if (!settings.enabled) {
            // Only on the switch from on to off, not on every tick while off
            if (voiceOutput != null) releaseVoice()
            return null
        }
        return voiceOutput ?: voiceOutputFactory(applicationContext).also { voiceOutput = it }
    }

    private fun releaseVoice() {
        voiceOutput?.release()
        voiceOutput = null
        lastAnnouncedGpsStatus = null
    }

    /** GPS, sensor and auto-pause events: under the master switch in both profiles, at once. */
    private fun sayStatus(kind: StatusKind) {
        voiceOutputFor(userPreferences.voiceSettings())?.say(VoiceLine.Status(kind))
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

    /** A GPS status change is an event: said on the session change, not on the tick. */
    private fun announceGpsStatus(session: WorkoutSession) {
        if (!(session.isTracking || session.isPaused)) return
        // Cheaper than the whole settings on every fix; the status needs only the master switch
        if (!userPreferences.voiceFeedback) {
            if (voiceOutput != null) releaseVoice()
            return
        }
        val announcement = GpsVoiceTransition.announcement(session.gpsStatus, lastAnnouncedGpsStatus)
        lastAnnouncedGpsStatus = session.gpsStatus
        when (announcement) {
            GpsAnnouncement.LOST -> sayStatus(StatusKind.GPS_LOST)
            GpsAnnouncement.UNRELIABLE -> sayStatus(StatusKind.GPS_UNRELIABLE)
            GpsAnnouncement.RECOVERED -> sayStatus(StatusKind.GPS_RECOVERED)
            null -> Unit
        }
    }

    /**
     * On the 1 s workout timer, after the clock ticked: the segments advance and the summaries
     * come due without waiting for a fix (a silent GPS does not delay them). The settings are
     * read live: a change mid-run applies at once. A manual pause stops the timer, so the voice
     * and the segments stand still. The segment and its target's verdict go to
     * [WorkoutSession.interval] for the screen: this engine is the only one of a running workout.
     *
     * Status lines are not in the coach's list: they are events said where they happen. They
     * still come in the spec's order (beep → status → warning → summary): the beep is outside the
     * speech queue, the auto-pause line is said by [tickAutoPause] earlier in the same tick, and
     * the GPS and sensor lines come between ticks, so all are queued before this tick's lines.
     */
    private fun tickVoice() {
        val session = sessionManager.getSession()
        if (!session.isTracking || session.isPaused) return
        val settings = userPreferences.voiceSettings()
        val engine = serviceIntervalEngine ?: run {
            rebuildIntervalEngineFromMetadata()
            serviceIntervalEngine
        }
        val result = voiceCoach.onTick(
            CoachInput(
                elapsedMs = session.currentTime,
                movingMs = session.movingTime,
                distanceM = session.distance * 1000f,
                autoPaused = session.autoPaused,
                avgPace = session.avgPace,
                settings = settings,
                heartRate = session.heartRateDisplay,
                engine = engine,
                livePace = livePaceTracker,
                // Live: HRmax changed mid-run applies on the next tick
                zones = if (engine != null) userPreferences.hrZones() else null,
                speechBusy = voiceOutput?.busy == true
            )
        )
        // The segment bar and the target indicator draw from this, not from an engine of their own
        sessionManager.setInterval(result.interval)
        if (engine != null) {
            val cursor = engine.snapshot()
            val changed = cursor != intervalCursor
            intervalCursor = cursor
            // A new segment, a beep or a warning consumed: saved now, not with the next fix (a
            // silent GPS may bring none), so a restore neither repeats them nor goes back
            if (changed) maybeSaveCheckpoint(session, force = true)
        }
        val output = voiceOutputFor(settings) ?: return
        result.lines.forEach(output::say)
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
        lastLocationTime = sessionClockMs()
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
                val currentTime = sessionClockMs()
                // Any fix counts here: while false fixes keep arriving there is nothing to pull
                if (currentTime - maxOf(lastLocationTime, lastAnyFixTime) > lostTimeoutMs) {
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
                                // Newer than any fix already processed, dropped ones included:
                                // re-feeding the last false fix would look like a frozen receiver
                                val newerThanTrack = location.time > maxOf(lastLocation?.time ?: 0L, lastProcessedFixTimeMs)
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
        val next = GpsStatusWatchdog.resolve(
            current = currentStatus,
            nowMs = sessionClockMs(),
            lastGoodFixMs = lastLocationTime,
            lastAnyFixMs = lastAnyFixTime,
            lostTimeoutMs = locationLostTimeoutMs()
        ) ?: return
        when (next) {
            GpsStatus.LOST -> android.util.Log.w("WorkoutTrackingService", "GPS signal lost during workout")
            GpsStatus.FOUND -> android.util.Log.i("WorkoutTrackingService", "GPS signal recovered during workout")
            else -> Unit
        }
        sessionManager.updateGpsStatus(next)
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
            val newLocationRequest = GpsConfig.createAdaptiveLocationRequest(adaptiveInterval)
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
        acquireWakeLock()
        workoutTimerJob = serviceScope.launch {
            var ticks = 0
            while (isActive && isCurrentlyTracking && !sessionManager.getSession().isPaused) {
                delay(WORKOUT_TIMER_INTERVAL_MS)
                withContext(Dispatchers.Main) {
                    if (++ticks % WAKE_LOCK_RENEW_EVERY_TICKS == 0) acquireWakeLock()
                    tickWorkout()
                }
            }
        }
    }

    /** One second of the running workout's timer; tests call it with their own [sessionClockMs]. */
    @VisibleForTesting
    internal fun tickWorkout() {
        countSilenceBySteps()
        tickAutoPause()
        // Advance clock without fan-out to checkpoint; UI + notification only
        sessionManager.tickElapsedTime(broadcast = false, now = sessionClockMs())
        tickLivePace()
        tickVoice()
        val session = sessionManager.getSession()
        sessionUpdateCallback?.invoke(session)
        notificationManager.updateNotification(session)
    }

    /**
     * The current-pace tile's pace ([WorkoutSession.livePace]) on the timer, after the clock
     * ticked, before the voice. GPS counts while the last good fix is fresh
     * ([LivePaceTracker.isGpsFresh]); otherwise the steps. An auto-pause starts the window over
     * on each of its ticks, so the drift at a light never enters it.
     */
    private fun tickLivePace() {
        val session = sessionManager.getSession()
        if (!session.isTracking || session.isPaused) return
        if (session.autoPaused) {
            livePaceTracker.restart(atMovingMs = session.movingTime)
            sessionManager.setLivePace(null)
            return
        }
        val fixAgeMs = lastLocationTime.takeIf { it > 0L }?.let { sessionClockMs() - it }
        val fresh = LivePaceTracker.isGpsFresh(
            fixAgeMs = fixAgeMs,
            requestIntervalMs = lastAppliedAdaptiveIntervalMs.takeIf { it > 0L } ?: GpsConfig.HIGH_ACCURACY_INTERVAL,
            inFalseSignal = gpsProcessor.inFalseSignal,
            status = session.gpsStatus
        )
        val tracker = stepTracker?.takeIf { it.isRunning }
        livePaceTracker.onTick(session.movingTime, session.distance * 1000f, tracker?.steps, fresh, fixAgeMs ?: 0L)
        sessionManager.setLivePace(livePaceTracker.pace())
    }

    /**
     * No fix arrives during a silence (tunnel, jamming, GNSS off under battery saver), so the
     * step distance of a silence is counted on the timer instead, see
     * [GpsLocationProcessor.countSilence].
     */
    private fun countSilenceBySteps() {
        val tracker = stepTracker?.takeIf { it.isRunning } ?: return
        // Monotonic, like the fixes' elapsedRealtime the filter measures a silence against
        val added = gpsProcessor.countSilence(SystemClock.elapsedRealtime(), tracker.steps, tracker.cadence)
        sessionManager.setOpenStepMeters(gpsProcessor.pendingStepMeters)
        sessionManager.addStepDistance(added, userPreferences.userWeight)
    }

    /**
     * Auto-pause applies to a running workout without interval segments when the setting is on.
     * The detector only decides; GPS, the watchdog and the step tracker go on untouched (the
     * track and the distance keep growing), only the moving clock stops. Steps are whatever the
     * running step tracker counts (GPS alone without it). The rules live in
     * [AutoPauseController], shared with the bench.
     */
    private fun tickAutoPause() {
        val transition = autoPause.tick(
            manager = sessionManager,
            enabled = userPreferences.autoPause && intervalSegmentsJson.isNullOrBlank(),
            steps = stepTracker?.takeIf { it.isRunning }?.steps,
            nowMono = SystemClock.elapsedRealtime(),
            nowWall = sessionClockMs()
        ) ?: return
        if (transition.announce) {
            when (AutoPauseVoiceTransition.announcement(transition.event, transition.closedAutoPauseMs)) {
                AutoPauseAnnouncement.PAUSED -> sayStatus(StatusKind.AUTO_PAUSE)
                AutoPauseAnnouncement.RESUMED -> sayStatus(StatusKind.AUTO_RESUME)
                null -> Unit
            }
        }
        val session = sessionManager.getSession()
        notificationManager.updateNotification(session, force = true)
        maybeSaveCheckpoint(session, force = true)
    }

    /** A fix's monotonic time in ms (now if the provider left it unset). */
    private fun monoTimeOf(location: Location): Long =
        location.elapsedRealtimeNanos.takeIf { it > 0L }?.let { it / 1_000_000L } ?: SystemClock.elapsedRealtime()

    private fun stopWorkoutTimer() {
        workoutTimerJob?.cancel()
        workoutTimerJob = null
        releaseWakeLock()
    }

    /** Not reference counted: a repeat call only restarts the timeout. */
    private fun acquireWakeLock() {
        wakeLock?.acquire(WAKE_LOCK_TIMEOUT_MS)
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
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
private fun GpsLocationProcessor.ProcessResult.toFixResult(): FixResult = when (this) {
    is GpsLocationProcessor.ProcessResult.Accepted -> FixResult.ACCEPTED
    is GpsLocationProcessor.ProcessResult.Rejected ->
        if (refreshGapClock) FixResult.NEAR_DUPLICATE else FixResult.REJECTED
}
