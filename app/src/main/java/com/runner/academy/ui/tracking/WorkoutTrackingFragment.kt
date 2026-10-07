package com.runner.academy.ui.tracking

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.runner.academy.util.GpsLocationClient
import com.runner.academy.R
import com.runner.academy.appContainer
import com.runner.academy.databinding.FragmentWorkoutTrackingBinding
import com.runner.academy.data.GpsStatus
import com.runner.academy.data.TrainingPlanRepository
import com.runner.academy.data.WorkoutState
import com.runner.academy.data.WorkoutSession
import com.runner.academy.util.IntervalSegmentsJson
import com.runner.academy.util.StepPermissionPolicy
import com.runner.academy.util.StepTrackingAccess
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.runner.academy.ui.workout.WorkoutDetailFragmentArgs
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.ceil

class WorkoutTrackingFragment : Fragment() {

    companion object {
        private const val TAG = "WorkoutTrackingFragment"
        private const val UI_UPDATE_THROTTLE = 500L
        private const val GPS_STATUS_UPDATE_INTERVAL = 2000L
        private const val STOP_HOLD_DURATION_MS = 3000L
        private const val STOP_HOLD_SECONDS = 3
        private const val STATE_MODE_SELECTION = "tracking_mode_selection"
        private const val STATE_INTERVAL_SEGMENTS_JSON = "tracking_interval_segments_json"
    }

    private var _binding: FragmentWorkoutTrackingBinding? = null
    private val binding get() = _binding!!

    private val viewModel: WorkoutTrackingViewModel by activityViewModels {
        val app = requireContext().appContainer()
        WorkoutTrackingViewModelFactory(
            app.workoutRepository,
            requireContext().applicationContext as android.app.Application
        )
    }

    private val planRepository: TrainingPlanRepository by lazy {
        requireContext().appContainer().trainingPlanRepository
    }

    // Managers / controllers
    private var mapManager: MapManager? = null
    private var gpsStatusUpdater: GpsStatusUiUpdater? = null
    private var panelStateManager: PanelStateManager? = null
    private var metricsDisplayManager: MetricsDisplayManager? = null
    private var modeController: TrackingModeController? = null
    private var intervalController: IntervalTrackingController? = null

    // State
    private var lastUIUpdateTime = 0L
    private var lastGpsStatusUpdate = 0L
    private var wasTrackingOrPaused = false
    private var isStoppingWorkout = false
    private var stopHoldJob: Job? = null
    private var stopHoldStartTime = 0L
    private var countdownJob: Job? = null
    private var stepPromptShowing = false

    // Periodic GPS status icon refresh (accuracy comes from session location)
    private val gpsStatusHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val gpsStatusRunnable = object : Runnable {
        override fun run() {
            gpsStatusUpdater?.updateStatusIcon()
            gpsStatusHandler.postDelayed(this, GPS_STATUS_UPDATE_INTERVAL)
        }
    }

    // -- Permission handlers --

    private fun onLocationPermissionReady() {
        mapManager?.initializeCenter()
        requestNotificationPermission()
    }

    private val locationPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions.getOrDefault(Manifest.permission.ACCESS_FINE_LOCATION, false)
        val coarseGranted = permissions.getOrDefault(Manifest.permission.ACCESS_COARSE_LOCATION, false)

        when {
            fineGranted -> onLocationPermissionReady()
            else -> {
                // GPS tracking needs precise location; "approximate" alone gives no fixes
                val message = if (coarseGranted) {
                    R.string.permission_precise_location_needed
                } else {
                    R.string.permission_location_needed
                }
                Toast.makeText(context, getString(message), Toast.LENGTH_LONG).show()
                findNavController().navigateUp()
            }
        }
    }

    private val notificationPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(requireContext(), getString(R.string.permission_notification_needed), Toast.LENGTH_LONG).show()
        }
    }

    /** One-time step permission at the first start; whatever the answer, the start goes on. */
    private val stepPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        stepPromptShowing = false
        if (!isGranted) StepTrackingAccess.decline(requireContext())
        if (_binding != null) startCountdown()
    }

    // -- Fragment lifecycle --

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentWorkoutTrackingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (savedInstanceState != null && viewModel.modeSelection.value == null) {
            restoreModeSelectionFromBundle(savedInstanceState)
        }
        if (savedInstanceState != null && viewModel.activeIntervalSegments.value == null) {
            restoreIntervalSegmentsFromBundle(savedInstanceState)
        }

        initializeManagers()
        modeController?.setupSpinner()
        modeController?.loadModes()
        setupClickListeners()
        observeViewModel()
        requestLocationPermission()
        setupBackButtonHandler()
        setupWindowInsets()
        gpsStatusUpdater?.updateStatusIcon()
        gpsStatusHandler.post(gpsStatusRunnable)
        updateIdleGpsBanner()

        // Always bind — after process/task death the VM is empty but the service
        // (or disk checkpoint) may still hold an active workout.
        viewModel.initializeService()
        intervalController?.maybeStartForActiveSession()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        TrackingModeSelectionCodec.encode(
            viewModel.modeSelection.value
                ?: modeController?.selectedMode?.toSelectionKey()
        )?.let { outState.putString(STATE_MODE_SELECTION, it) }
        intervalController?.segmentsJsonForSave()?.let { json ->
            outState.putString(STATE_INTERVAL_SEGMENTS_JSON, json)
        } ?: viewModel.activeIntervalSegments.value?.let { segments ->
            IntervalSegmentsJson.toJson(segments)?.let {
                outState.putString(STATE_INTERVAL_SEGMENTS_JSON, it)
            }
        }
    }

    private fun restoreModeSelectionFromBundle(state: Bundle) {
        val selection = TrackingModeSelectionCodec.decode(state.getString(STATE_MODE_SELECTION))
            ?: return
        viewModel.setModeSelection(selection)
    }

    private fun restoreIntervalSegmentsFromBundle(state: Bundle) {
        val json = state.getString(STATE_INTERVAL_SEGMENTS_JSON) ?: return
        val segments = IntervalSegmentsJson.parse(json)
        if (segments.isNotEmpty()) {
            viewModel.setActiveIntervalSegments(segments)
        }
    }

    private fun initializeManagers() {
        modeController = TrackingModeController(
            context = requireContext(),
            binding = binding,
            viewModel = viewModel,
            planRepository = planRepository,
            scope = viewLifecycleOwner.lifecycleScope,
            onModeApplied = {
                intervalController?.updatePreview()
                intervalController?.maybeStartForActiveSession()
                updateAutoPauseUnavailable()
            }
        )
        intervalController = IntervalTrackingController(
            context = requireContext(),
            binding = binding,
            viewModel = viewModel,
            selectedMode = { modeController?.selectedMode ?: TrackingWorkoutMode.EasyRun }
        )

        gpsStatusUpdater = GpsStatusUiUpdater(requireContext(), GpsStatusUiUpdater.Views(
            layoutGpsStatus = binding.layoutGpsStatus,
            textViewGpsAccuracy = binding.textViewGpsAccuracy,
            viewGpsBar1 = binding.viewGpsBar1,
            viewGpsBar2 = binding.viewGpsBar2,
            viewGpsBar3 = binding.viewGpsBar3,
            viewGpsBar4 = binding.viewGpsBar4,
        ))

        mapManager = MapManager(requireContext(), binding.mapView, object : MapManager.Callbacks {
            override fun onMapCenteredStateChanged(centered: Boolean) {
                updateCenterButtonVisibility(centered)
            }

            override fun getCurrentWorkoutSession(): WorkoutSession? {
                return viewModel.workoutSession.value
            }

            override fun onLocationFix(location: Location) {
                if (_binding == null || !isAdded || isDetached) return
                val session = viewModel.workoutSession.value
                if (session.isTracking || session.isPaused) return
                gpsStatusUpdater?.setGpsData(location.accuracy, System.currentTimeMillis())
                gpsStatusUpdater?.updateStatusIcon()
                viewModel.seedPreStartLocation(location)
                updateIdleGpsBanner()
            }
        })
        mapManager?.initialize()
        updateCenterButtonVisibility(true)

        panelStateManager = PanelStateManager(PanelStateManager.Views(
            layoutWorkoutPanel = binding.layoutWorkoutPanel,
            layoutExpandedInfo = binding.layoutExpandedInfo,
            textViewWorkoutTime = binding.textViewWorkoutTime,
            textViewWorkoutDistance = binding.textViewWorkoutDistance,
            // The whole caption (label or pause chip) hides with the collapsed timer
            textViewWorkoutTimeLabel = binding.layoutTimerCaption,
            textViewWorkoutDistanceLabel = binding.textViewWorkoutDistanceLabel,
        ))
        panelStateManager?.setupGesture(requireContext())

        metricsDisplayManager = MetricsDisplayManager(
            MetricsDisplayManager.Views(
                textViewWorkoutTime = binding.textViewWorkoutTime,
                textViewWorkoutDistance = binding.textViewWorkoutDistance,
                textViewWorkoutTimeExpanded = binding.textViewWorkoutTimeExpanded,
                textViewWorkoutDistanceExpanded = binding.textViewWorkoutDistanceExpanded,
                textViewWorkoutPace = binding.textViewWorkoutPace,
                textViewWorkoutHeartRate = binding.textViewWorkoutHeartRate,
                textViewAvgSpeed = binding.textViewAvgSpeed,
                textViewCurrentPace = binding.textViewCurrentPace,
                textViewCaloriesBurned = binding.textViewCaloriesBurned,
                spinnerWorkoutType = binding.spinnerWorkoutType,
                buttonStart = binding.buttonStart,
                buttonPause = binding.buttonPause,
                buttonStop = binding.buttonStop,
                timerCaption = MetricsDisplayManager.TimerCaption(
                    container = binding.layoutTimerCaption,
                    label = binding.textViewWorkoutTimeLabel,
                    chip = binding.textViewTimerStateChip
                ),
                timerCaptionExpanded = MetricsDisplayManager.TimerCaption(
                    container = binding.layoutTimerCaptionExpanded,
                    label = binding.textViewWorkoutTimeLabelExpanded,
                    chip = binding.textViewTimerStateChipExpanded
                ),
                textViewElapsedTimeExpanded = binding.textViewElapsedTimeExpanded,
            ),
            viewModel
        ) { expanded ->
            intervalController?.onExpandedInfoChanged(expanded)
        }
    }

    // -- Setup methods --

    private fun setupBackButtonHandler() {
        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val currentState = viewModel.workoutState.value

                // «Назад» (и свайп от края экрана) не завершает тренировку: остановка только
                // удержанием «Стоп», чтобы случайный жест не оборвал пробежку.
                if (currentState == WorkoutState.RUNNING || currentState == WorkoutState.PAUSED) {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.back_ignored_during_workout, STOP_HOLD_SECONDS),
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    // Если тренировка не активна, просто возвращаемся назад
                    findNavController().navigateUp()
                }
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, callback)
    }

    private fun setupClickListeners() {
        binding.buttonStart.setOnClickListener {
            startAfterStepPermissionPrompt()
        }

        binding.buttonPause.setOnClickListener {
            val currentState = viewModel.workoutState.value
            when (currentState) {
                WorkoutState.RUNNING -> viewModel.pauseWorkout()
                WorkoutState.PAUSED -> viewModel.resumeWorkout()
                else -> {}
            }
        }

        setupStopButton()

        binding.buttonMyLocation.setOnClickListener {
            mapManager?.centerOnCurrentLocation()
        }
    }

    private fun setupStopButton() {
        binding.buttonStop.setOnTouchListener { view, event ->
            if (!view.isEnabled) return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startStopHold()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (stopHoldJob != null) {
                        val inside = event.x >= 0 && event.y >= 0 && event.x <= view.width && event.y <= view.height
                        if (!inside) cancelStopHold()
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val elapsed = SystemClock.elapsedRealtime() - stopHoldStartTime
                    if (elapsed >= STOP_HOLD_DURATION_MS) {
                        stopHoldJob?.cancel()
                        stopHoldJob = null
                        completeStopHold()
                    } else {
                        cancelStopHold()
                    }
                    view.performClick()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    cancelStopHold()
                    true
                }
                else -> false
            }
        }
    }

    private fun startStopHold() {
        stopHoldJob?.cancel()
        stopHoldStartTime = SystemClock.elapsedRealtime()
        binding.layoutStopHold.visibility = View.VISIBLE
        binding.stopHoldBackground.visibility = View.VISIBLE
        binding.progressBarStopHold.progress = 0
        binding.textViewStopHoldHint.text = getString(R.string.stop_hold_hint, STOP_HOLD_SECONDS)
        binding.textViewStopHoldCountdown.text = STOP_HOLD_SECONDS.toString()

        stopHoldJob = viewLifecycleOwner.lifecycleScope.launch {
            while (true) {
                val elapsed = SystemClock.elapsedRealtime() - stopHoldStartTime
                val progress = (elapsed.toFloat() / STOP_HOLD_DURATION_MS).coerceIn(0f, 1f)
                binding.progressBarStopHold.progress =
                    (progress * binding.progressBarStopHold.max).toInt()

                if (elapsed >= STOP_HOLD_DURATION_MS) {
                    stopHoldJob = null
                    completeStopHold()
                    return@launch
                }

                val remainingMillis = (STOP_HOLD_DURATION_MS - elapsed).coerceAtLeast(0L)
                val remainingSeconds = ceil(remainingMillis / 1000.0).toInt().coerceAtLeast(1)
                binding.textViewStopHoldCountdown.text = remainingSeconds.toString()
                binding.textViewStopHoldHint.text =
                    getString(R.string.stop_hold_hint, remainingSeconds)
                delay(50)
            }
        }
    }

    private fun cancelStopHold() {
        stopHoldJob?.cancel()
        stopHoldJob = null
        stopHoldStartTime = 0L
        binding.layoutStopHold.visibility = View.GONE
        binding.stopHoldBackground.visibility = View.GONE
        binding.progressBarStopHold.progress = 0
        binding.textViewStopHoldHint.text = getString(R.string.stop_hold_hint, STOP_HOLD_SECONDS)
        binding.textViewStopHoldCountdown.text = STOP_HOLD_SECONDS.toString()
        binding.buttonStop.isPressed = false
        // Не сбрасываем isStoppingWorkout здесь, так как тренировка может быть остановлена
    }

    private fun completeStopHold() {
        if (isStoppingWorkout) {
            return // Уже идет процесс остановки
        }
        isStoppingWorkout = true
        stopHoldStartTime = 0L
        binding.layoutStopHold.visibility = View.GONE
        binding.stopHoldBackground.visibility = View.GONE
        binding.progressBarStopHold.progress = 0
        binding.textViewStopHoldHint.text = getString(R.string.stop_hold_hint, STOP_HOLD_SECONDS)
        binding.textViewStopHoldCountdown.text = STOP_HOLD_SECONDS.toString()
        binding.buttonStop.isPressed = false
        viewModel.stopWorkout()
        navigateToWorkoutDetails()
    }

    private fun setupWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.layoutWorkoutPanel) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.updatePadding(left = bars.left, right = bars.right, bottom = bars.bottom)
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.layoutGpsStatus) { v, insets ->
            val cut = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            val base = resources.getDimensionPixelSize(R.dimen.activity_vertical_margin)
            v.updatePadding(top = base + cut.top)
            insets
        }
    }

    // -- ViewModel observation --

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
                    viewModel.workoutSession.collect { session ->
                        if (isAdded && !isDetached) {
                            updateUI(session)
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    android.util.Log.d(TAG, "Session loading cancelled")
                    throw e
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
                    viewModel.workoutState.collect { state ->
                        if (isAdded && !isDetached) {
                            metricsDisplayManager?.updateButtonStates(state)
                            updateAutoPauseUnavailable()
                            if (state == WorkoutState.RUNNING || state == WorkoutState.PAUSED) {
                                modeController?.syncFromViewModel()
                                intervalController?.maybeStartForActiveSession()
                            }
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    android.util.Log.d(TAG, "State loading cancelled")
                    throw e
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
                    viewModel.modeSelection.collect { selection ->
                        if (isAdded && !isDetached && selection != null) {
                            modeController?.syncFromViewModel()
                            intervalController?.maybeStartForActiveSession()
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    android.util.Log.d(TAG, "Mode selection cancelled")
                    throw e
                }
            }
        }
    }

    // -- UI updates --

    private fun updateUI(session: WorkoutSession) {
        if (_binding == null || !isAdded || isDetached) return
        val now = System.currentTimeMillis()

        val tracking = session.isTracking || session.isPaused
        gpsStatusUpdater?.sessionStatus = if (tracking) session.gpsStatus else null

        // Map updates always run to keep track current (respect GPS gaps; no position on a false signal)
        mapManager?.updateTrackFromDataPoints(
            session.trackDataPoints,
            session.currentLocation,
            session.gpsStatus.takeIf { tracking }
        )
        mapManager?.updateMapOrientation(session)
        updateGpsBanner(session)
        intervalController?.update(session)

        val forceNumericRefresh = session.gpsStatus == GpsStatus.LOST
            || session.gpsStatus == GpsStatus.DENIED
            || session.gpsStatus == GpsStatus.SEARCHING

        if (!forceNumericRefresh && now - lastUIUpdateTime < UI_UPDATE_THROTTLE) {
            return
        }
        lastUIUpdateTime = now

        metricsDisplayManager?.updateMetrics(session)

        if (wasTrackingOrPaused && !tracking) {
            mapManager?.resumePreWorkoutLocationUpdates()
            updateIdleGpsBanner()
        }
        wasTrackingOrPaused = tracking

        if (tracking) {
            gpsStatusUpdater?.updateGpsSignalIndicator(
                session.gpsStatus,
                session.currentLocation?.accuracy ?: gpsStatusUpdater?.getLastGpsAccuracy() ?: 0f
            )
            session.currentLocation?.let { location ->
                gpsStatusUpdater?.setGpsData(location.accuracy, System.currentTimeMillis())
            }
        }

        val gpsStatusTime = System.currentTimeMillis()
        if (gpsStatusTime - lastGpsStatusUpdate >= GPS_STATUS_UPDATE_INTERVAL) {
            // Idle: refresh from last fallback fix. Tracking: keep bars in sync with freshness.
            gpsStatusUpdater?.updateStatusIcon()
            lastGpsStatusUpdate = gpsStatusTime
        }

        mapManager?.autoCenterIfNeeded(session)
    }

    /**
     * Before the start only: auto-pause is on but the chosen mode has segments, where it does
     * not apply. Gone once the workout runs (no auto-pause chip in an interval workout either).
     */
    private fun updateAutoPauseUnavailable() {
        if (_binding == null) return
        val idle = viewModel.workoutState.value == WorkoutState.NOT_STARTED
        val withSegments = modeController?.selectedMode?.hasIntervals() == true
        val show = idle && withSegments && requireContext().appContainer().userPreferences.autoPause
        binding.textViewAutoPauseUnavailable.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun updateGpsBanner(session: WorkoutSession) {
        val banner = binding.textViewGpsBanner
        when {
            session.gpsStatus == GpsStatus.LOST && session.isTracking -> {
                banner.visibility = View.VISIBLE
                banner.text = getString(
                    if (session.strideModelState != null) R.string.gps_lost_banner_steps else R.string.gps_lost_banner
                )
            }
            session.gpsStatus == GpsStatus.UNRELIABLE && session.isTracking -> {
                banner.visibility = View.VISIBLE
                // Steps count only when this run started them (allowed and a sensor at start)
                banner.text = getString(
                    if (session.strideModelState != null) R.string.gps_unreliable_banner else R.string.gps_unreliable_banner_no_steps
                )
            }
            !session.isTracking && !session.isPaused -> {
                updateIdleGpsBanner()
            }
            else -> banner.visibility = View.GONE
        }
    }

    private fun updateIdleGpsBanner() {
        val banner = _binding?.textViewGpsBanner ?: return
        val session = viewModel.workoutSession.value
        if (session.isTracking || session.isPaused) return
        val updater = gpsStatusUpdater ?: return
        val status = com.runner.academy.util.ErrorHandler.determineGpsStatus(
            requireContext(),
            updater.getLastGpsAccuracy(),
            updater.getLastGpsUpdateTime()
        )
        when (status) {
            GpsStatus.FOUND, GpsStatus.STRONG, GpsStatus.MEDIUM, GpsStatus.WEAK -> {
                banner.visibility = View.VISIBLE
                banner.text = getString(R.string.gps_ready_banner)
            }
            // Accuracy alone never yields UNRELIABLE; listed for exhaustiveness.
            GpsStatus.SEARCHING, GpsStatus.LOST, GpsStatus.UNRELIABLE -> {
                banner.visibility = View.VISIBLE
                banner.text = getString(R.string.gps_wait_banner)
            }
            GpsStatus.DENIED -> {
                banner.visibility = View.VISIBLE
                banner.text = getString(R.string.gps_accuracy_denied)
            }
        }
    }

    private fun isPreStartGpsReady(): Boolean {
        val updater = gpsStatusUpdater ?: return false
        val status = com.runner.academy.util.ErrorHandler.determineGpsStatus(
            requireContext(),
            updater.getLastGpsAccuracy(),
            updater.getLastGpsUpdateTime()
        )
        return status == GpsStatus.FOUND ||
            status == GpsStatus.STRONG ||
            status == GpsStatus.MEDIUM ||
            status == GpsStatus.WEAK
    }

    // -- Workout start/stop flow --

    /**
     * On API 29+ asks for ACTIVITY_RECOGNITION once, at the first workout start, with a short
     * rationale (steps for distance when GPS is lost). Any answer continues to the countdown;
     * a refusal turns the setting off and is never asked again automatically.
     */
    private fun startAfterStepPermissionPrompt() {
        if (stepPromptShowing || countdownJob != null) return
        val context = requireContext()
        if (!StepTrackingAccess.shouldPromptAtWorkoutStart(context) ||
            !StepTrackingAccess.hasStepSensor(context)
        ) {
            startCountdown()
            return
        }
        StepTrackingAccess.markPromptShown(context)
        stepPromptShowing = true
        var requested = false
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.steps_permission_title)
            .setMessage(R.string.steps_permission_rationale)
            .setPositiveButton(R.string.steps_permission_allow) { _, _ ->
                requested = true
                stepPermissionRequest.launch(StepPermissionPolicy.PERMISSION)
            }
            // Only an explicit answer turns steps off: "Not now", back or a tap outside
            .setNegativeButton(R.string.steps_permission_not_now) { _, _ -> StepTrackingAccess.decline(context) }
            .setOnCancelListener { StepTrackingAccess.decline(context) }
            .setOnDismissListener {
                if (requested) return@setOnDismissListener
                stepPromptShowing = false
                // Recreated (theme, locale): no answer was given, the user taps Start again
                if (activity?.isChangingConfigurations == true) return@setOnDismissListener
                if (_binding != null) startCountdown()
            }
            .show()
    }

    private fun startCountdown() {
        if (countdownJob != null) return
        if (!isPreStartGpsReady()) {
            Toast.makeText(requireContext(), R.string.gps_wait_banner, Toast.LENGTH_SHORT).show()
            // Still allow start — outdoor athletes may know better — but warn clearly
        }
        viewModel.seedPreStartLocation(mapManager?.peekLastLocation())
        val prefs = requireContext().appContainer().userPreferences
        val countdownSeconds = prefs.startCountdownSeconds
        if (countdownSeconds <= 0) {
            beginWorkout()
            return
        }
        binding.spinnerWorkoutType.visibility = View.GONE
        binding.buttonStart.visibility = View.GONE
        binding.textViewCountdown.visibility = View.VISIBLE
        binding.textViewCountdown.text = countdownSeconds.toString()
        countdownJob = viewLifecycleOwner.lifecycleScope.launch {
            for (i in countdownSeconds downTo 1) {
                val b = _binding ?: return@launch
                b.textViewCountdown.text = i.toString()
                if (i > 1) delay(1000)
            }
            val b = _binding ?: return@launch
            b.textViewCountdown.visibility = View.GONE
            countdownJob = null
            beginWorkout()
        }
    }

    private fun beginWorkout() {
        isStoppingWorkout = false
        intervalController?.prepareForNewWorkout()
        showBatteryOptimizationHintOnce()
        viewModel.seedPreStartLocation(mapManager?.peekLastLocation())
        viewLifecycleOwner.lifecycleScope.launch {
            val workoutType = modeController?.selectedWorkoutType
                ?: com.runner.academy.data.WorkoutType.EASY_RUN
            val started = viewModel.startWorkoutWhenReady(workoutType)
            if (!started && isAdded) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.error_start_workout),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun navigateToWorkoutDetails() {
        val session = viewModel.workoutSession.value

        if (session.currentTime > 0) {
            binding.textViewGpsAccuracy.visibility = View.GONE
            binding.buttonStop.isEnabled = false

            val needsDistancePrompt = session.distance <= 0f &&
                session.trackDataPoints.size < 2

            if (needsDistancePrompt) {
                showManualDistanceDialog { manualKm ->
                    persistAndOpenDetails(manualKm)
                }
            } else {
                persistAndOpenDetails(manualDistanceKm = null)
            }
        } else {
            Toast.makeText(context, getString(R.string.no_data_to_save), Toast.LENGTH_SHORT).show()
            isStoppingWorkout = false
        }
    }

    private fun showManualDistanceDialog(onResult: (Float?) -> Unit) {
        val input = android.widget.EditText(requireContext()).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = getString(R.string.save_distance_hint)
        }

        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.save_distance_dialog_title)
            .setMessage(R.string.save_distance_dialog_message)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                val km = input.text.toString().replace(',', '.').toFloatOrNull()
                if (km != null && km >= 0f) {
                    onResult(km)
                } else {
                    onResult(0f)
                }
            }
            .setNeutralButton(R.string.save_without_distance) { _, _ ->
                onResult(0f)
            }
            .setNegativeButton(R.string.cancel) { _, _ ->
                _binding?.buttonStop?.isEnabled = true
                isStoppingWorkout = false
            }
            .setOnCancelListener {
                _binding?.buttonStop?.isEnabled = true
                isStoppingWorkout = false
            }
            .show()
    }

    private fun persistAndOpenDetails(manualDistanceKm: Float?) {
        viewLifecycleOwner.lifecycleScope.launch {
            var savedOk = false
            try {
                val session = viewModel.workoutSession.value
                val intervalJson = intervalController?.segmentsJsonForSave()
                val workoutType = modeController?.selectedWorkoutType
                    ?: com.runner.academy.data.WorkoutType.EASY_RUN
                val workoutId = viewModel.saveWorkoutToDatabase(
                    workoutType,
                    manualDistanceKm = manualDistanceKm,
                    intervalSegmentsJson = intervalJson
                )
                if (workoutId != null) {
                    savedOk = true
                    intervalController?.activeScheduledId?.let { scheduledId ->
                        planRepository.markScheduledDone(scheduledId, workoutId)
                        modeController?.refreshTodaysScheduled()
                    }
                    val distanceForToast = manualDistanceKm ?: session.distance
                    val ctx = context
                    if (ctx != null) {
                        Toast.makeText(
                            ctx,
                            String.format(
                                getString(R.string.workout_saved_format),
                                distanceForToast,
                                viewModel.formatTime(session.currentTime)
                            ),
                            Toast.LENGTH_LONG
                        ).show()
                    }

                    if (isAdded && !isDetached) {
                        findNavController().navigate(
                            R.id.nav_workout_detail,
                            WorkoutDetailFragmentArgs(workoutId = workoutId, justSaved = true).toBundle()
                        )
                    }
                } else if (_binding != null && isAdded) {
                    com.runner.academy.util.ErrorHandler.handleSaveError(
                        requireContext(),
                        Exception("Failed to save workout")
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (_binding != null && isAdded) {
                    com.runner.academy.util.ErrorHandler.handleSaveError(requireContext(), e)
                }
            } finally {
                if (savedOk) {
                    intervalController?.clear()
                    _binding?.textViewGpsAccuracy?.visibility = View.GONE
                    _binding?.buttonStop?.isEnabled = true
                    viewModel.resetWorkout()
                    isStoppingWorkout = false
                    if (_binding != null && isAdded) {
                        modeController?.loadModes()
                    }
                } else {
                    _binding?.buttonStop?.isEnabled = true
                    isStoppingWorkout = false
                }
            }
        }
    }

    // -- Permissions & system requests --

    private fun requestLocationPermission() {
        when {
            GpsLocationClient.hasPrecisePermission(requireContext()) -> onLocationPermissionReady()
            else -> {
                locationPermissionRequest.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                )
            }
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(
                    requireContext(),
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED -> {}
                else -> notificationPermissionRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    /**
     * One-time, non-blocking hint. Opens the general battery-optimization list, which does not
     * need REQUEST_IGNORE_BATTERY_OPTIMIZATIONS (restricted by Google Play policy); the location
     * foreground service keeps tracking alive in most cases anyway.
     */
    private fun showBatteryOptimizationHintOnce() {
        val context = requireContext()
        val prefs = context.appContainer().userPreferences
        if (prefs.batteryOptimizationHintShown) return
        val powerManager = context.getSystemService(android.content.Context.POWER_SERVICE) as PowerManager
        if (powerManager.isIgnoringBatteryOptimizations(context.packageName)) return
        prefs.batteryOptimizationHintShown = true
        com.google.android.material.snackbar.Snackbar
            .make(binding.root, R.string.battery_optimization_hint, com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
            .setAction(R.string.battery_optimization_open_settings) {
                try {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                } catch (e: Exception) {
                    android.util.Log.w("WorkoutTracking", "Battery optimization settings unavailable: ${e.message}")
                }
            }
            .show()
    }

    // -- Map center button visibility --

    private fun updateCenterButtonVisibility(centered: Boolean) {
        val visibility = if (centered) View.GONE else View.VISIBLE
        _binding?.buttonMyLocationContainer?.visibility = visibility
        _binding?.buttonMyLocation?.visibility = visibility
    }

    // -- Lifecycle --

    override fun onResume() {
        super.onResume()
        mapManager?.onResume()
        // The setting may have changed meanwhile
        updateAutoPauseUnavailable()
    }

    override fun onPause() {
        super.onPause()
        mapManager?.onPause()
    }

    override fun onDestroyView() {
        super.onDestroyView()

        mapManager?.cleanupAutoCenter()
        gpsStatusHandler.removeCallbacks(gpsStatusRunnable)

        cancelStopHold()

        // Do NOT call viewModel.cleanup() here — activity-scoped ViewModel must stay
        // bound to WorkoutTrackingService across Fragment view recreation / navigation.

        mapManager?.onDetach()
        mapManager = null
        gpsStatusUpdater = null
        panelStateManager = null
        metricsDisplayManager = null
        modeController = null
        intervalController = null
        _binding = null
    }
}
