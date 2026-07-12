package com.drrhaos.runner.ui.tracking

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.drrhaos.runner.R
import com.drrhaos.runner.databinding.FragmentWorkoutTrackingBinding
import com.drrhaos.runner.data.GpsStatus
import com.drrhaos.runner.data.WorkoutDatabase
import com.drrhaos.runner.data.WorkoutState
import com.drrhaos.runner.data.WorkoutType
import com.drrhaos.runner.data.WorkoutSession
import com.drrhaos.runner.ui.settings.SettingsViewModel
import com.drrhaos.runner.ui.settings.SettingsViewModelFactory
import com.drrhaos.runner.util.GpsConfig
import com.drrhaos.runner.util.UserPreferences
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
    }

    private var _binding: FragmentWorkoutTrackingBinding? = null
    private val binding get() = _binding!!

    private val viewModel: WorkoutTrackingViewModel by viewModels {
        val database = WorkoutDatabase.getDatabase(requireContext())
        val repository = com.drrhaos.runner.data.WorkoutRepository(database.workoutDao())
        WorkoutTrackingViewModelFactory(repository, requireContext().applicationContext as android.app.Application)
    }

    private val settingsViewModel: SettingsViewModel by viewModels {
        SettingsViewModelFactory(requireContext())
    }

    // Managers
    private var mapManager: MapManager? = null
    private var gpsStatusUpdater: GpsStatusUiUpdater? = null
    private var voiceFeedbackManager: VoiceFeedbackManager? = null
    private var panelStateManager: PanelStateManager? = null
    private var metricsDisplayManager: MetricsDisplayManager? = null

    // State
    private var selectedWorkoutType: WorkoutType = WorkoutType.EASY_RUN
    private var isVoiceEnabled = false
    private var lastUIUpdateTime = 0L
    private var lastGpsStatusUpdate = 0L
    private var isStoppingWorkout = false
    private var stopHoldJob: Job? = null
    private var stopHoldStartTime = 0L
    private var countdownJob: Job? = null

    // GPS monitoring via LocationManager
    private lateinit var locationManager: android.location.LocationManager
    private val gpsStatusListener = object : android.location.LocationListener {
        override fun onLocationChanged(location: android.location.Location) {
            gpsStatusUpdater?.setGpsData(location.accuracy, System.currentTimeMillis())
        }

        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {
            gpsStatusUpdater?.setGpsData(0f, 0L)
        }
    }

    // Periodic GPS status update
    private val gpsStatusHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val gpsStatusRunnable = object : Runnable {
        override fun run() {
            gpsStatusUpdater?.updateStatusIcon()
            gpsStatusHandler.postDelayed(this, GPS_STATUS_UPDATE_INTERVAL)
        }
    }

    private fun stripUnit(value: String): String = MetricsDisplayManager.stripUnit(value)

    // -- Permission handlers --

    private val locationPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        when {
            permissions.getOrDefault(Manifest.permission.ACCESS_FINE_LOCATION, false) -> {
                mapManager?.initializeCenter()
                viewModel.initializeLocationClient(requireContext())
                requestNotificationPermission()
            }
            permissions.getOrDefault(Manifest.permission.ACCESS_COARSE_LOCATION, false) -> {
                mapManager?.initializeCenter()
                viewModel.initializeLocationClient(requireContext())
                requestNotificationPermission()
            }
            else -> {
                Toast.makeText(context, getString(R.string.permission_location_needed), Toast.LENGTH_LONG).show()
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

    // -- Fragment lifecycle --

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        voiceFeedbackManager = VoiceFeedbackManager(requireContext())
        voiceFeedbackManager?.initTTS()
        _binding = FragmentWorkoutTrackingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initializeManagers()
        setupWorkoutTypeSpinner()
        setupClickListeners()
        observeViewModel()
        requestLocationPermission()
        setupBackButtonHandler()
        setupWindowInsets()
        setupGpsStatusMonitoring()

        gpsStatusUpdater?.updateStatusIcon()
        gpsStatusHandler.post(gpsStatusRunnable)

        // If workout already active, reconnect to service
        val ws = viewModel.workoutSession.value
        if (ws.isTracking || ws.isPaused) {
            viewModel.initializeService()
        }
    }

    private fun initializeManagers() {
        // MapManager
        mapManager = MapManager(requireContext(), binding.mapView, object : MapManager.Callbacks {
            override fun onMapCenteredStateChanged(centered: Boolean) {
                updateCenterButtonVisibility(centered)
            }

            override fun getCurrentWorkoutSession(): WorkoutSession? {
                return viewModel.workoutSession.value
            }
        })
        mapManager?.initialize()
        updateCenterButtonVisibility(true)

        // GpsStatusUiUpdater
        gpsStatusUpdater = GpsStatusUiUpdater(requireContext(), GpsStatusUiUpdater.Views(
            layoutGpsStatus = binding.layoutGpsStatus,
            textViewGpsAccuracy = binding.textViewGpsAccuracy,
            viewGpsBar1 = binding.viewGpsBar1,
            viewGpsBar2 = binding.viewGpsBar2,
            viewGpsBar3 = binding.viewGpsBar3,
            viewGpsBar4 = binding.viewGpsBar4,
        ))

        // PanelStateManager
        panelStateManager = PanelStateManager(PanelStateManager.Views(
            layoutWorkoutPanel = binding.layoutWorkoutPanel,
            layoutExpandedInfo = binding.layoutExpandedInfo,
            textViewWorkoutTime = binding.textViewWorkoutTime,
            textViewWorkoutDistance = binding.textViewWorkoutDistance,
            textViewWorkoutTimeLabel = binding.textViewWorkoutTimeLabel,
            textViewWorkoutDistanceLabel = binding.textViewWorkoutDistanceLabel,
        ))
        panelStateManager?.setupGesture(requireContext())

        // MetricsDisplayManager
        metricsDisplayManager = MetricsDisplayManager(MetricsDisplayManager.Views(
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
        ), viewModel)
    }

    // -- Setup methods --

    private fun setupBackButtonHandler() {
        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val currentState = viewModel.workoutState.value
                val session = viewModel.workoutSession.value

                if (currentState == WorkoutState.RUNNING || currentState == WorkoutState.PAUSED) {
                    if (isStoppingWorkout) return
                    isStoppingWorkout = true
                    cancelStopHold()
                    viewModel.stopWorkout()
                    if (session.distance > 0 && session.currentTime > 0) {
                        navigateToWorkoutDetails()
                    } else {
                        findNavController().navigateUp()
                    }
                } else {
                    findNavController().navigateUp()
                }
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, callback)
    }

    private fun setupWorkoutTypeSpinner() {
        val workoutTypes = listOf(
            WorkoutType.EASY_RUN,
            WorkoutType.TEMPO_RUN,
            WorkoutType.INTERVAL_TRAINING,
            WorkoutType.LONG_RUN,
            WorkoutType.RECOVERY_RUN,
            WorkoutType.RACE
        )
        val typeNames = workoutTypes.map { type ->
            when (type) {
                WorkoutType.EASY_RUN -> getString(R.string.workout_type_easy_run)
                WorkoutType.TEMPO_RUN -> getString(R.string.workout_type_tempo_run)
                WorkoutType.INTERVAL_TRAINING -> getString(R.string.workout_type_interval_training)
                WorkoutType.LONG_RUN -> getString(R.string.workout_type_long_run)
                WorkoutType.RECOVERY_RUN -> getString(R.string.workout_type_recovery_run)
                WorkoutType.RACE -> getString(R.string.workout_type_competition)
            }
        }
        val adapter = android.widget.ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, typeNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerWorkoutType.adapter = adapter
        binding.spinnerWorkoutType.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedWorkoutType = workoutTypes[position]
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
    }

    private fun setupClickListeners() {
        binding.buttonStart.setOnClickListener {
            startCountdown()
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
    }

    private fun completeStopHold() {
        if (isStoppingWorkout) return
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

    private fun setupGpsStatusMonitoring() {
        try {
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
            ) {
                locationManager = requireContext().getSystemService(android.content.Context.LOCATION_SERVICE) as android.location.LocationManager
                locationManager.requestLocationUpdates(
                    android.location.LocationManager.GPS_PROVIDER,
                    GpsConfig.MEDIUM_ACCURACY_INTERVAL,
                    GpsConfig.MIN_DISTANCE,
                    gpsStatusListener
                )
            }
        } catch (e: Exception) {
            android.util.Log.e("GPSStatus", "Failed to setup GPS status monitoring: ${e.message}")
        }
    }

    // -- ViewModel observation --

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                viewModel.workoutSession.collect { session ->
                    if (isAdded && !isDetached) {
                        updateUI(session)
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                android.util.Log.d(TAG, "Session loading cancelled")
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                viewModel.workoutState.collect { state ->
                    if (isAdded && !isDetached) {
                        metricsDisplayManager?.updateButtonStates(state)
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                android.util.Log.d(TAG, "State loading cancelled")
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                settingsViewModel.settingsState.collect { state ->
                    if (isAdded && !isDetached) {
                        isVoiceEnabled = state.voiceFeedback
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                android.util.Log.d(TAG, "Settings loading cancelled")
            }
        }
    }

    // -- UI updates --

    private fun updateUI(session: WorkoutSession) {
        val now = System.currentTimeMillis()

        // Map updates always run to keep track current
        mapManager?.updateTrack(session.trackPoints, session.currentLocation)
        mapManager?.updateMapOrientation(session)

        val forceNumericRefresh = session.gpsStatus == GpsStatus.LOST
            || session.gpsStatus == GpsStatus.DENIED
            || session.gpsStatus == GpsStatus.SEARCHING

        if (!forceNumericRefresh && now - lastUIUpdateTime < UI_UPDATE_THROTTLE) {
            if (isVoiceEnabled) {
                voiceFeedbackManager?.notifyDistance(session)
            }
            return
        }
        lastUIUpdateTime = now

        metricsDisplayManager?.updateMetrics(session)
        gpsStatusUpdater?.updateGpsSignalIndicator(session.gpsStatus, gpsStatusUpdater?.getLastGpsAccuracy() ?: 0f)

        session.currentLocation?.let { location ->
            gpsStatusUpdater?.setGpsData(location.accuracy, System.currentTimeMillis())
        }

        val gpsStatusTime = System.currentTimeMillis()
        if (gpsStatusTime - lastGpsStatusUpdate >= GPS_STATUS_UPDATE_INTERVAL) {
            gpsStatusUpdater?.updateStatusIcon()
            lastGpsStatusUpdate = gpsStatusTime
        }

        mapManager?.autoCenterIfNeeded(session)

        if (isVoiceEnabled) {
            voiceFeedbackManager?.notifyDistance(session)
        }
    }

    // -- Workout start/stop flow --

    private fun startCountdown() {
        if (countdownJob != null) return
        val prefs = UserPreferences(requireContext())
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
                binding.textViewCountdown.text = i.toString()
                if (i > 1) delay(1000)
            }
            binding.textViewCountdown.visibility = View.GONE
            countdownJob = null
            beginWorkout()
        }
    }

    private fun beginWorkout() {
        isStoppingWorkout = false
        voiceFeedbackManager?.resetMilestones()
        requestBatteryOptimizationExemption()
        viewModel.initializeService()
        viewLifecycleOwner.lifecycleScope.launch {
            delay(500)
            viewModel.startWorkoutWithRetry(selectedWorkoutType)
        }
        binding.textViewGpsAccuracy.visibility = View.GONE
    }

    private fun navigateToWorkoutDetails() {
        val session = viewModel.workoutSession.value

        if (session.distance > 0 && session.currentTime > 0) {
            binding.textViewGpsAccuracy.visibility = View.GONE
            binding.buttonStop.isEnabled = false

            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val workoutId = viewModel.saveWorkoutToDatabase(selectedWorkoutType)
                    if (workoutId != null) {
                        Toast.makeText(context, String.format(
                            getString(R.string.workout_saved_format),
                            session.distance,
                            viewModel.formatTime(session.currentTime)
                        ), Toast.LENGTH_LONG).show()

                        val bundle = Bundle().apply {
                            putLong("workoutId", workoutId)
                        }
                        findNavController().navigate(com.drrhaos.runner.R.id.nav_workout_detail, bundle)
                    } else {
                        com.drrhaos.runner.util.ErrorHandler.handleSaveError(
                            requireContext(),
                            Exception("Failed to save workout")
                        )
                    }
                } catch (e: Exception) {
                    com.drrhaos.runner.util.ErrorHandler.handleSaveError(requireContext(), e)
                } finally {
                    binding.textViewGpsAccuracy.visibility = View.GONE
                    binding.buttonStop.isEnabled = true
                    viewModel.resetWorkout()
                    isStoppingWorkout = false
                }
            }
        } else {
            Toast.makeText(context, getString(R.string.no_data_to_save), Toast.LENGTH_SHORT).show()
            isStoppingWorkout = false
        }
    }

    // -- Permissions & system requests --

    private fun requestLocationPermission() {
        when {
            ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED -> {
                mapManager?.initializeCenter()
                viewModel.initializeLocationClient(requireContext())
                requestNotificationPermission()
            }
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

    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = requireContext().getSystemService(android.content.Context.POWER_SERVICE) as PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(requireContext().packageName)) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = android.net.Uri.parse("package:${requireContext().packageName}")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.battery_optimization_hint),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
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
    }

    override fun onPause() {
        super.onPause()
        mapManager?.onPause()
    }

    override fun onDestroyView() {
        super.onDestroyView()

        mapManager?.cleanupAutoCenter()
        gpsStatusHandler.removeCallbacks(gpsStatusRunnable)

        try {
            if (::locationManager.isInitialized) {
                locationManager.removeUpdates(gpsStatusListener)
            }
        } catch (e: Exception) {
            android.util.Log.e("GPSStatus", "Failed to stop GPS status monitoring: ${e.message}")
        }

        cancelStopHold()

        try {
            viewModel.cleanup()
        } catch (e: Exception) {
            // ViewModel may not be initialized
        }

        voiceFeedbackManager?.destroy()
        mapManager?.onDetach()
        mapManager = null
        gpsStatusUpdater = null
        panelStateManager = null
        metricsDisplayManager = null
        _binding = null
    }
}
