package com.drrhaos.runner.ui.tracking

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.graphics.Color
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.drrhaos.runner.data.WorkoutDatabase
import com.drrhaos.runner.data.WorkoutType
import com.drrhaos.runner.util.GpsFilter
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.drrhaos.runner.R
import com.drrhaos.runner.databinding.FragmentWorkoutTrackingBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import android.location.LocationManager
import android.location.LocationListener
import android.location.Location
import android.speech.tts.TextToSpeech
import android.util.Log
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import android.view.MotionEvent
import android.view.GestureDetector
import android.view.GestureDetector.SimpleOnGestureListener
import com.drrhaos.runner.ui.settings.SettingsViewModel
import com.drrhaos.runner.ui.settings.SettingsViewModelFactory
import com.drrhaos.runner.util.FormatUtils
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import kotlin.math.ceil


class WorkoutTrackingFragment : Fragment() {

    companion object {
        private const val TAG = "WorkoutTrackingFragment"
        
        // Timing constants
        private const val AUTO_CENTER_DELAY = 5000L // 5 секунд неактивности для возврата к текущему местоположению
        private const val UI_UPDATE_THROTTLE = 500L
        private const val GPS_STATUS_UPDATE_INTERVAL = 2000L
        private const val MAP_UPDATE_THROTTLE = 1000L
        private const val STOP_HOLD_DURATION_MS = 3000L
        private const val STOP_HOLD_SECONDS = 3
        
        // Map configuration
        private const val DEFAULT_ZOOM_LEVEL = 16.0
        private const val TRACK_LINE_WIDTH = 8f
        private const val TRACK_LINE_COLOR = Color.RED
        
        // GPS accuracy thresholds
        private const val EXCELLENT_ACCURACY = 5f
        private const val GOOD_ACCURACY = 10f
        private const val FAIR_ACCURACY = 20f

        // Direction calculation
        private const val DIRECTION_WINDOW_SIZE = 10
    }

    private var _binding: FragmentWorkoutTrackingBinding? = null
    private val binding get() = _binding!!
    private var tts: TextToSpeech? = null
    private val viewModel: WorkoutTrackingViewModel by viewModels {
        val database = WorkoutDatabase.getDatabase(requireContext())
        WorkoutTrackingViewModelFactory(database.workoutDao(), requireContext())
    }

    private val settingsViewModel: SettingsViewModel by viewModels {
        SettingsViewModelFactory(requireContext())
    }

    // Map components
    private var mapView: MapView? = null
    private var locationOverlay: MyLocationNewOverlay? = null
    private var trackPolyline: Polyline? = null
    private var selectedWorkoutType: WorkoutType = WorkoutType.EASY_RUN
    
    // User interaction tracking
    private var isUserInteractingWithMap = false
    private var isMapCenteredOnUser = true
    private var lastUserInteractionTime = 0L
    private var lastMapUpdateTime = 0L
    private var lastUIUpdateTime = 0L

    // Voice notification stuff
    private var lastDistanceKmVoiceSpoken = -1
    private var isVoiceEnabled = false

    // GPS status tracking
    private var lastGpsAccuracy = 0f
    private var lastGpsUpdateTime = 0L
    private var lastGpsStatusUpdate = 0L
    
    // Handler для периодического обновления GPS статуса
    private val gpsStatusHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val gpsStatusRunnable = object : Runnable {
        override fun run() {
            updateGpsStatusIcon()
            gpsStatusHandler.postDelayed(this, GPS_STATUS_UPDATE_INTERVAL)
        }
    }
    
    // LocationManager для постоянного мониторинга GPS
    private lateinit var locationManager: LocationManager
    private val gpsStatusListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            // Обновляем GPS данные для статуса
            lastGpsAccuracy = location.accuracy
            lastGpsUpdateTime = System.currentTimeMillis()
            
            
            android.util.Log.d(TAG, "GPS location updated via LocationManager: accuracy=${location.accuracy}m")
        }
        
        override fun onProviderEnabled(provider: String) {
            android.util.Log.d("GPSStatus", "GPS provider enabled: $provider")
        }
        
        override fun onProviderDisabled(provider: String) {
            android.util.Log.d("GPSStatus", "GPS provider disabled: $provider")
            lastGpsUpdateTime = 0L // Сбрасываем время последнего обновления
        }
    }
    
    // Управление панелью
    private var isPanelExpanded = false
    private lateinit var gestureDetector: GestureDetector
    private var lastTapTime = 0L
    private var stopHoldJob: Job? = null
    private var stopHoldStartTime = 0L
    private var isStoppingWorkout = false
    private var countdownJob: Job? = null

    private fun gpsStatusShortLabel(status: GpsStatus): String = when (status) {
        GpsStatus.SEARCHING -> getString(R.string.gps_signal_searching)
        GpsStatus.WEAK -> getString(R.string.gps_signal_weak)
        GpsStatus.MEDIUM -> getString(R.string.gps_signal_medium)
        GpsStatus.STRONG, GpsStatus.FOUND -> getString(R.string.gps_signal_strong)
        GpsStatus.LOST -> getString(R.string.gps_signal_lost)
        GpsStatus.DENIED -> getString(R.string.gps_signal_denied)
    }

    private fun updateGpsSignalIndicator(status: GpsStatus, accuracy: Float) {
        when (status) {
            GpsStatus.SEARCHING -> {
                binding.layoutGpsStatus.visibility = View.VISIBLE
                setGpsBarsLevel(1)
                binding.textViewGpsAccuracy.visibility = View.VISIBLE
                binding.textViewGpsAccuracy.text = getString(R.string.gps_accuracy_searching)
            }
            GpsStatus.WEAK -> {
                binding.layoutGpsStatus.visibility = View.VISIBLE
                setGpsBarsLevel(2)
                binding.textViewGpsAccuracy.visibility = View.VISIBLE
                binding.textViewGpsAccuracy.text = getString(R.string.gps_accuracy_value, accuracy.toInt())
            }
            GpsStatus.MEDIUM -> {
                binding.layoutGpsStatus.visibility = View.VISIBLE
                setGpsBarsLevel(3)
                binding.textViewGpsAccuracy.visibility = View.VISIBLE
                binding.textViewGpsAccuracy.text = getString(R.string.gps_accuracy_value, accuracy.toInt())
            }
            GpsStatus.STRONG, GpsStatus.FOUND -> {
                binding.layoutGpsStatus.visibility = View.VISIBLE
                setGpsBarsLevel(4)
                binding.textViewGpsAccuracy.visibility = View.INVISIBLE
            }
            GpsStatus.LOST -> {
                binding.layoutGpsStatus.visibility = View.VISIBLE
                setGpsBarsLevel(1)
                binding.textViewGpsAccuracy.visibility = View.VISIBLE
                binding.textViewGpsAccuracy.text = getString(R.string.gps_accuracy_lost)
            }
            GpsStatus.DENIED -> {
                binding.layoutGpsStatus.visibility = View.VISIBLE
                setGpsBarsLevel(0)
                binding.textViewGpsAccuracy.visibility = View.VISIBLE
                binding.textViewGpsAccuracy.text = getString(R.string.gps_accuracy_denied)
            }
        }
        val label = gpsStatusShortLabel(status)
        binding.layoutGpsStatus.contentDescription = getString(R.string.gps_status_a11y, label)
    }

    private fun setGpsBarsLevel(level: Int) {
        val bars = listOf(
            binding.viewGpsBar1,
            binding.viewGpsBar2,
            binding.viewGpsBar3,
            binding.viewGpsBar4
        )
        val inactiveColor = ContextCompat.getColor(requireContext(), R.color.gps_signal_inactive)
        val activeColor = when (level) {
            4 -> ContextCompat.getColor(requireContext(), R.color.gps_signal_level_4)
            3 -> ContextCompat.getColor(requireContext(), R.color.gps_signal_level_3)
            2 -> ContextCompat.getColor(requireContext(), R.color.gps_signal_level_2)
            1 -> ContextCompat.getColor(requireContext(), R.color.gps_signal_level_1)
            else -> inactiveColor
        }
        bars.forEachIndexed { index, view ->
            val background = view.background?.let { DrawableCompat.wrap(it).mutate() }
            val color = if (index < level) activeColor else inactiveColor
            background?.let {
                DrawableCompat.setTint(it, color)
                view.background = it
            }
            view.alpha = if (index < level) 1f else 0.3f
        }
    }

    private fun setMapCenteredState(centered: Boolean) {
        if (isMapCenteredOnUser != centered) {
            isMapCenteredOnUser = centered
            updateCenterButtonVisibility()
        }
    }

    private fun updateCenterButtonVisibility() {
        val visibility = if (isMapCenteredOnUser) View.GONE else View.VISIBLE
        _binding?.buttonMyLocationContainer?.visibility = visibility
        _binding?.buttonMyLocation?.visibility = visibility
    }

    private val locationPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        when {
            permissions.getOrDefault(Manifest.permission.ACCESS_FINE_LOCATION, false) -> {
                initializeMap()
                viewModel.initializeLocationClient(requireContext())
                requestNotificationPermission()
                requestBackgroundLocationPermission()
                requestActivityRecognitionPermission()
            }
            permissions.getOrDefault(Manifest.permission.ACCESS_COARSE_LOCATION, false) -> {
                initializeMap()
                viewModel.initializeLocationClient(requireContext())
                requestNotificationPermission()
                requestBackgroundLocationPermission()
                requestActivityRecognitionPermission()
            }
            permissions.getOrDefault(Manifest.permission.ACTIVITY_RECOGNITION, false) -> {
                // Разрешение на распознавание физической активности предоставлено
                Toast.makeText(context, getString(R.string.permission_activity_granted), Toast.LENGTH_SHORT).show()
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

    private fun stripUnit(value: String): String {
        val trimmed = value.trim()
        val spaceIndex = trimmed.indexOf(' ')
        return if (spaceIndex > 0) trimmed.substring(0, spaceIndex) else trimmed
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        initTTS()
        _binding = FragmentWorkoutTrackingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupMap()
        setupWorkoutTypeSpinner()
        setupClickListeners()
        setupSwipeGesture()
        observeViewModel()
        requestLocationPermission()
        updateCenterButtonVisibility()
        setupBackButtonHandler()
        setupWindowInsets()

        // Инициализируем GPS статус
        try {
            android.util.Log.d("GPSStatus", "Initializing GPS status indicator")
            // GPS layout is always initialized at this point
            
            // Инициализируем LocationManager для постоянного мониторинга GPS
            locationManager = requireContext().getSystemService(android.content.Context.LOCATION_SERVICE) as LocationManager
            setupGpsStatusMonitoring()
            
            updateGpsStatusIcon()
            
            // Запускаем периодическое обновление GPS статуса
            gpsStatusHandler.post(gpsStatusRunnable)
            
            android.util.Log.d("GPSStatus", "GPS status initialized successfully")
        } catch (e: Exception) {
            android.util.Log.e("GPSStatus", "Failed to initialize GPS status: ${e.message}")
        }
        
        // Сервис инициализируется только при старте тренировки для экономии батареи;
        // при возврате на экран во время активной тренировки — переподключаемся к сервису.
        val ws = viewModel.workoutSession.value
        if (ws.isTracking || ws.isPaused) {
            viewModel.initializeService()
        }
    }
    
    private fun setupBackButtonHandler() {
        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val currentState = viewModel.workoutState.value
                val session = viewModel.workoutSession.value
                
                // Если тренировка активна (запущена или на паузе), останавливаем её
                if (currentState == WorkoutState.RUNNING || currentState == WorkoutState.PAUSED) {
                    android.util.Log.d("WorkoutTracking", "Back button pressed during active workout, stopping workout")
                    
                    // Если уже идет процесс остановки, не делаем ничего
                    if (isStoppingWorkout) {
                        return
                    }
                    
                    isStoppingWorkout = true
                    
                    // Отменяем удержание кнопки остановки, если оно активно
                    cancelStopHold()
                    
                    // Останавливаем тренировку
                    viewModel.stopWorkout()
                    
                    // Если есть данные для сохранения, сохраняем и переходим к деталям
                    if (session.distance > 0 && session.currentTime > 0) {
                        navigateToWorkoutDetails()
                    } else {
                        // Если данных нет, просто возвращаемся назад
                        findNavController().navigateUp()
                    }
                } else {
                    // Если тренировка не активна, просто возвращаемся назад
                    findNavController().navigateUp()
                }
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, callback)
    }

    private fun setupMap() {
        // Конфигурация OSMDroid
        Configuration.getInstance().load(requireContext(), requireContext().getSharedPreferences("osmdroid", 0))
        
        mapView = binding.mapView
        mapView?.setTileSource(TileSourceFactory.MAPNIK)
        mapView?.setMultiTouchControls(true)
        // setBuiltInZoomControls is deprecated - zoom controls are disabled by default in modern OSMDroid
        mapView?.setClickable(true)
        mapView?.isHorizontalMapRepetitionEnabled = false
        mapView?.isVerticalMapRepetitionEnabled = false
        
        // Дополнительные настройки для отключения встроенных элементов управления
        mapView?.setFlingEnabled(true)
        mapView?.setScrollableAreaLimitLatitude(MapView.getTileSystem().maxLatitude, MapView.getTileSystem().minLatitude, 0)
        
        // Отключаем дополнительные элементы управления OSMDroid
        mapView?.setUseDataConnection(true)
        mapView?.setKeepScreenOn(true)
        mapView?.controller?.setZoom(DEFAULT_ZOOM_LEVEL)
        
        // Добавляем слушатели взаимодействия с картой
        setupMapInteractionListeners()
        
        // Настройка карты для отображения местоположения с кастомной иконкой
        locationOverlay = MyLocationNewOverlay(GpsMyLocationProvider(requireContext()), mapView)
        locationOverlay?.enableMyLocation()
        locationOverlay?.enableFollowLocation() // Включаем следование за местоположением
        locationOverlay?.setDrawAccuracyEnabled(false) // Отключаем отображение точности
        
        setupLocationIcon()
        
        mapView?.overlays?.add(locationOverlay)
        
        // Инициализация полилинии для трека
        trackPolyline = Polyline().apply {
            outlinePaint.color = TRACK_LINE_COLOR
            outlinePaint.strokeWidth = TRACK_LINE_WIDTH
        }
        mapView?.overlays?.add(trackPolyline)
    }

    private fun setupLocationIcon() {
        try {
            val customIcon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_location_no_arrow)
            customIcon?.let { icon ->
                val bitmap = createBitmapFromDrawable(icon)
                locationOverlay?.setPersonIcon(bitmap)
                locationOverlay?.setDirectionIcon(bitmap)
                android.util.Log.d(TAG, "Custom location icon set successfully")
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to set custom location icon: ${e.message}")
        }
    }

    private fun createBitmapFromDrawable(drawable: android.graphics.drawable.Drawable): android.graphics.Bitmap {
        val bitmap = android.graphics.Bitmap.createBitmap(
            drawable.intrinsicWidth,
            drawable.intrinsicHeight,
            android.graphics.Bitmap.Config.ARGB_8888
        )
        val canvas = android.graphics.Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
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

    private fun startCountdown() {
        if (countdownJob != null) return
        val prefs = com.drrhaos.runner.util.UserPreferences(requireContext())
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
                if (i > 1) {
                    delay(1000)
                }
            }
            binding.textViewCountdown.visibility = View.GONE
            countdownJob = null
            beginWorkout()
        }
    }

    private fun beginWorkout() {
        isStoppingWorkout = false
        lastDistanceKmVoiceSpoken = -1
        requestBatteryOptimizationExemption()
        viewModel.initializeService()
        viewLifecycleOwner.lifecycleScope.launch {
            delay(500)
            viewModel.startWorkoutWithRetry(selectedWorkoutType)
        }
        binding.textViewGpsAccuracy.visibility = View.GONE
    }

    private fun setupMapInteractionListeners() {
        // Слушаем изменения масштаба через GestureDetector
        val scaleDetector = android.view.ScaleGestureDetector(requireContext(), object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: android.view.ScaleGestureDetector): Boolean {
                isUserInteractingWithMap = true
                lastUserInteractionTime = System.currentTimeMillis()
                setMapCenteredState(false)
                scheduleAutoCenter()
                return super.onScale(detector)
            }
        })
        
        // Добавляем обработку жестов масштабирования к OnTouchListener
        mapView?.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            when (event.action) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    isUserInteractingWithMap = true
                    lastUserInteractionTime = System.currentTimeMillis()
                    setMapCenteredState(false)
                    // Отменяем предыдущую задачу, т.к. пользователь активно взаимодействует с картой
                    mapView?.handler?.removeCallbacks(autoCenterRunnable)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    // Планируем автоматическое центрирование через задержку после завершения взаимодействия
                    scheduleAutoCenter()
                }
            }
            false // Не блокируем обработку касаний картой
        }
        
        // Добавляем MapListener для отслеживания всех изменений карты (zoom, pan, scroll)
        mapView?.addMapListener(object : MapListener {
            override fun onScroll(event: ScrollEvent?): Boolean {
                isUserInteractingWithMap = true
                lastUserInteractionTime = System.currentTimeMillis()
                setMapCenteredState(false)
                scheduleAutoCenter()
                return false // Позволяем обработку события дальше
            }
            
            override fun onZoom(event: ZoomEvent?): Boolean {
                isUserInteractingWithMap = true
                lastUserInteractionTime = System.currentTimeMillis()
                setMapCenteredState(false)
                scheduleAutoCenter()
                return false // Позволяем обработку события дальше
            }
        })
    }

    private fun scheduleAutoCenter() {
        // Отменяем предыдущую задачу автопоиска
        mapView?.handler?.removeCallbacks(autoCenterRunnable)
        // Планируем новую задачу через задержку
        mapView?.handler?.postDelayed(autoCenterRunnable, AUTO_CENTER_DELAY)
    }

    private val autoCenterRunnable = Runnable {
        isUserInteractingWithMap = false
        // Автоматически центрируем карту на текущем местоположении
        autoCenterOnLocation()
    }

    private fun autoCenterOnLocation() {
        val session = viewModel.workoutSession.value
        session.currentLocation?.let { location ->
            val geoPoint = GpsFilter.createValidGeoPoint(location)
            if (geoPoint != null) {
                // Используем плавную анимацию для автопоиска
                mapView?.controller?.animateTo(geoPoint, 16.0, 1000L)
                setMapCenteredState(true)
            } else {
                android.util.Log.w("WorkoutTracking", "Invalid GPS coordinates for auto center")
            }
            
            // Обновляем ориентацию карты по направлению движения
            val bearing = getDirectionBearing(session.trackPoints)
            if (bearing >= 0) {
                try {
                    val mapOrientation = -bearing
                    mapView?.mapOrientation = mapOrientation
                    android.util.Log.d("AutoCenter", "Auto-centered map and updated orientation: bearing=$bearing, mapOrientation=$mapOrientation")
                } catch (e: Exception) {
                    android.util.Log.e("AutoCenter", "Error updating map orientation: ${e.message}", e)
                }
            }
            
            lastMapUpdateTime = System.currentTimeMillis()
        }
    }
    
    private fun setupGpsStatusMonitoring() {
        try {
            // Проверяем разрешения
            if (androidx.core.content.ContextCompat.checkSelfPermission(requireContext(), 
                android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                
                // Запрашиваем обновления местоположения для мониторинга GPS статуса
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    com.drrhaos.runner.util.GpsConfig.MEDIUM_ACCURACY_INTERVAL, // 5 секунд
                    com.drrhaos.runner.util.GpsConfig.MIN_DISTANCE, // 5 метров
                    gpsStatusListener
                )
                
                android.util.Log.d("GPSStatus", "GPS status monitoring started")
            } else {
                android.util.Log.w("GPSStatus", "Location permission not granted for GPS status monitoring")
            }
        } catch (e: Exception) {
            android.util.Log.e("GPSStatus", "Failed to setup GPS status monitoring: ${e.message}")
        }
    }
    
    private fun updateGpsStatusIcon() {
        val gpsStatus = com.drrhaos.runner.util.ErrorHandler.determineGpsStatus(
            requireContext(),
            lastGpsAccuracy,
            lastGpsUpdateTime
        )
        updateGpsSignalIndicator(gpsStatus, lastGpsAccuracy)
    }

    private fun setupSwipeGesture() {
        gestureDetector = GestureDetector(requireContext(), object : SimpleOnGestureListener() {
            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                e1?.let {
                    val diffY = e2.y - it.y
                    val diffX = e2.x - it.x
                    
                    // Движение пальца вверх по области информации для развертывания
                    if (Math.abs(diffY) > Math.abs(diffX) && diffY < 0 && Math.abs(diffY) > 100) {
                        if (!isPanelExpanded) {
                            expandPanel()
                            return true
                        }
                    }
                    // Движение пальца вниз для свертывания
                    else if (Math.abs(diffY) > Math.abs(diffX) && diffY > 0 && Math.abs(diffY) > 100) {
                        if (isPanelExpanded) {
                            collapsePanel()
                            return true
                        }
                    }
                    return true
                }
                return false
            }

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                return handleDoubleTap()
            }
        })

        // Устанавливаем gesture detector в кастомный LinearLayout
        (binding.layoutWorkoutPanel as TouchInterceptingLinearLayout).setGestureDetector(gestureDetector)

    }

    private fun handleDoubleTap(): Boolean {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastTapTime < 300) { // 300ms для двойного тапа
            // Двойной тап - переключаем состояние панели
            if (isPanelExpanded) {
                collapsePanel()
            } else {
                expandPanel()
            }
            return true
        }
        lastTapTime = currentTime
        return false
    }

    private fun expandPanel() {
        isPanelExpanded = true
        binding.layoutExpandedInfo.visibility = android.view.View.VISIBLE
        binding.textViewWorkoutTime.visibility = View.GONE
        binding.textViewWorkoutDistance.visibility = View.GONE
        binding.textViewWorkoutTimeLabel.visibility = View.GONE
        binding.textViewWorkoutDistanceLabel.visibility = View.GONE
        
        // Анимация появления расширенной информации
        binding.layoutExpandedInfo.alpha = 0f
        binding.layoutExpandedInfo.animate()
            .alpha(1f)
            .setDuration(300)
            .start()
    }

    private fun collapsePanel() {
        isPanelExpanded = false
        
        // Анимация исчезновения расширенной информации
        binding.layoutExpandedInfo.animate()
            .alpha(0f)
            .setDuration(300)
            .withEndAction {
                binding.layoutExpandedInfo.visibility = android.view.View.GONE
                binding.textViewWorkoutTime.visibility = View.VISIBLE
                binding.textViewWorkoutDistance.visibility = View.VISIBLE
                binding.textViewWorkoutTimeLabel.visibility = View.VISIBLE
                binding.textViewWorkoutDistanceLabel.visibility = View.VISIBLE
            }
            .start()
    }

    private fun initializeMap() {
        mapView?.let { map ->
            // Центрируем карту на текущем местоположении
            locationOverlay?.myLocation?.let { geoPoint ->
                // Проверяем валидность координат GeoPoint
                if (geoPoint.latitude in -90.0..90.0 && geoPoint.longitude in -180.0..180.0) {
                    map.controller?.setCenter(geoPoint)
                } else {
                    android.util.Log.w("WorkoutTracking", "Invalid GPS coordinates for map center")
                }
            }
        }
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

    private fun setupClickListeners() {
        binding.buttonStart.setOnClickListener {
            startCountdown()
        }

        binding.buttonPause.setOnClickListener {
            val currentState = viewModel.workoutState.value
            android.util.Log.d("WorkoutTracking", "Pause button clicked, current state: $currentState")
            
            when (currentState) {
                WorkoutState.RUNNING -> {
                    android.util.Log.d("WorkoutTracking", "Pausing workout")
                    viewModel.pauseWorkout()
                }
                WorkoutState.PAUSED -> {
                    android.util.Log.d("WorkoutTracking", "Resuming workout")
                    viewModel.resumeWorkout()
                }
                else -> {
                    android.util.Log.w("WorkoutTracking", "Pause button clicked in unexpected state: $currentState")
                }
            }
        }

        binding.buttonStop.setOnTouchListener { view, event ->
            if (!view.isEnabled) {
                return@setOnTouchListener false
            }
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startStopHold()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (stopHoldJob != null) {
                        val inside = event.x >= 0 && event.y >= 0 && event.x <= view.width && event.y <= view.height
                        if (!inside) {
                            cancelStopHold()
                        }
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

        // Кнопка "Моё местоположение"
        binding.buttonMyLocation.setOnClickListener {
            centerMapOnCurrentLocation()
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

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                viewModel.workoutSession.collect { session ->
                    if (isAdded && !isDetached) {
                        updateUI(session)
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                android.util.Log.d("WorkoutTracking", "Session loading cancelled")
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                viewModel.workoutState.collect { state ->
                    if (isAdded && !isDetached) {
                        updateButtonStates(state)
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                android.util.Log.d("WorkoutTracking", "State loading cancelled")
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
                android.util.Log.d("WorkoutTracking", "Settings loading cancelled")
            }
        }
    }

    private fun updateUI(session: WorkoutSession) {
        val now = System.currentTimeMillis()

        // Карта и ориентация — всегда, чтобы трек не «отставал» от метрик при троттлинге текста
        updateTrackOnMap(session.trackPoints, session.currentLocation)
        updateMapOrientation(session)

        val forceNumericRefresh = session.gpsStatus == GpsStatus.LOST
            || session.gpsStatus == GpsStatus.DENIED
            || session.gpsStatus == GpsStatus.SEARCHING

        if (!forceNumericRefresh && now - lastUIUpdateTime < UI_UPDATE_THROTTLE) {
            if (isVoiceEnabled) {
                launchVoiceNotification(session)
            }
            return
        }
        lastUIUpdateTime = now

        android.util.Log.d("WorkoutTracking", "updateUI called: isTracking=${session.isTracking}, isPaused=${session.isPaused}, currentTime=${session.currentTime}")

        updateGpsSignalIndicator(session.gpsStatus, lastGpsAccuracy)

        val formattedTime = viewModel.formatTime(session.currentTime)
        val formattedDistance = String.format("%.2f", session.distance)
        binding.textViewWorkoutTime.text = formattedTime
        binding.textViewWorkoutDistance.text = formattedDistance
        binding.textViewWorkoutTimeExpanded.text = formattedTime
        binding.textViewWorkoutDistanceExpanded.text = formattedDistance
        binding.textViewWorkoutPace.text = stripUnit(viewModel.formatPace(session.avgPace))
        binding.textViewWorkoutHeartRate.text = if (session.heartRate > 0) session.heartRate.toString() else "--"
        binding.textViewAvgSpeed.text = stripUnit(viewModel.formatSpeed(session.avgSpeed))
        binding.textViewCurrentPace.text = stripUnit(viewModel.formatPace(session.currentPace))
        binding.textViewCaloriesBurned.text = session.calories.toString()

        session.currentLocation?.let { location ->
            lastGpsAccuracy = location.accuracy
            lastGpsUpdateTime = System.currentTimeMillis()
            android.util.Log.d("GPSStatus", "GPS location updated: accuracy=${location.accuracy}m")
        }

        val gpsStatusTime = System.currentTimeMillis()
        if (gpsStatusTime - lastGpsStatusUpdate >= GPS_STATUS_UPDATE_INTERVAL) {
            updateGpsStatusIcon()
            lastGpsStatusUpdate = gpsStatusTime
        }

        session.currentLocation?.let { location ->
            if (session.gpsStatus == GpsStatus.FOUND) {
                if (!isUserInteractingWithMap) {
                    val mapTick = System.currentTimeMillis()
                    if (mapTick - lastMapUpdateTime > 2000) {
                        val geoPoint = GpsFilter.createValidGeoPoint(location)
                        if (geoPoint != null) {
                            mapView?.controller?.animateTo(geoPoint, 16.0, 1000L)
                            setMapCenteredState(true)
                            lastMapUpdateTime = mapTick
                        } else {
                            android.util.Log.w("WorkoutTracking", "Invalid GPS coordinates for map update")
                        }
                    }
                } else {
                    scheduleAutoCenter()
                }
            }
        }

        if (isVoiceEnabled) {
            launchVoiceNotification(session)
        }
    }

    private fun updateButtonStates(state: WorkoutState) {
        android.util.Log.d("WorkoutTracking", "updateButtonStates called with state: $state")
        
        when (state) {
            WorkoutState.NOT_STARTED -> {
                android.util.Log.d("WorkoutTracking", "Setting buttons to NOT_STARTED state")
                // 1. активна кнопка старта (не запущена)
                binding.spinnerWorkoutType.visibility = View.VISIBLE
                binding.buttonStart.visibility = View.VISIBLE
                binding.buttonPause.visibility = View.GONE
                binding.buttonStop.visibility = View.GONE
            }
            WorkoutState.RUNNING -> {
                android.util.Log.d("WorkoutTracking", "Setting buttons to RUNNING state")
                // 2. активна кнопка паузы и остановки (запущена)
                binding.spinnerWorkoutType.visibility = View.GONE
                binding.buttonStart.visibility = View.GONE
                binding.buttonPause.visibility = View.VISIBLE
                binding.buttonStop.visibility = View.VISIBLE
                binding.buttonPause.setImageResource(R.drawable.ic_pause)
            }
            WorkoutState.PAUSED -> {
                android.util.Log.d("WorkoutTracking", "Setting buttons to PAUSED state")
                // 3. активна кнопка возобновить и стоп (пауза)
                binding.spinnerWorkoutType.visibility = View.GONE
                binding.buttonStart.visibility = View.GONE
                binding.buttonPause.visibility = View.VISIBLE
                binding.buttonStop.visibility = View.VISIBLE
                binding.buttonPause.setImageResource(R.drawable.ic_play_arrow)
            }
            WorkoutState.STOPPED -> {
                android.util.Log.d("WorkoutTracking", "Setting buttons to STOPPED state")
                // Возвращаемся к начальному состоянию
                binding.spinnerWorkoutType.visibility = View.VISIBLE
                binding.buttonStart.visibility = View.VISIBLE
                binding.buttonPause.visibility = View.GONE
                binding.buttonStop.visibility = View.GONE
            }
        }
    }

    private fun updateTrackOnMap(trackPoints: List<GeoPoint>, currentLocation: Location? = null) {
        if (trackPoints.isNotEmpty()) {
            val pointsWithCurrent = trackPoints.toMutableList()
            currentLocation?.let {
                pointsWithCurrent.add(GeoPoint(it.latitude, it.longitude))
            }
            @Suppress("DEPRECATION")
            val currentPoints = trackPolyline?.points ?: emptyList()
            if (pointsWithCurrent.size != currentPoints.size) {
                trackPolyline?.setPoints(pointsWithCurrent)
                mapView?.invalidate()
                android.util.Log.d(TAG, "Track updated: ${pointsWithCurrent.size} points")
            }
        } else {
            trackPolyline?.setPoints(mutableListOf())
            mapView?.invalidate()
        }
    }

    private fun launchVoiceNotification(session: WorkoutSession) {
        val completedKm = kotlin.math.floor(session.distance.toDouble()).toInt()
        if (completedKm < 1) return
        if (completedKm <= lastDistanceKmVoiceSpoken) return

        lastDistanceKmVoiceSpoken = completedKm

        val distanceTTS = FormatUtils.formatDistanceForTTS(session.distance, requireContext())
        val timeTTS = FormatUtils.formatTimeForTTS(session.currentTime, requireContext())
        val paceTTS = FormatUtils.formatPaceForTTS(session.avgPace, requireContext())

        val notification = getString(R.string.voice_notif_text_each_km, distanceTTS, timeTTS, paceTTS)
        tts?.speak(notification, TextToSpeech.QUEUE_ADD, null, "distance_${completedKm}km")
    }

    private fun calculateBearing(from: GeoPoint, to: GeoPoint): Float {
        val lat1 = Math.toRadians(from.latitude)
        val lat2 = Math.toRadians(to.latitude)
        val deltaLon = Math.toRadians(to.longitude - from.longitude)
        
        val y = Math.sin(deltaLon) * Math.cos(lat2)
        val x = Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(deltaLon)
        
        var bearing = Math.toDegrees(Math.atan2(y, x))
        bearing = (bearing + 360) % 360
        
        return bearing.toFloat()
    }

    private fun getDirectionBearing(trackPoints: List<GeoPoint>): Float {
        if (trackPoints.size < 2) return -1f
        val windowSize = minOf(DIRECTION_WINDOW_SIZE, trackPoints.size)
        val from = trackPoints[trackPoints.size - windowSize]
        val to = trackPoints.last()
        return calculateBearing(from, to)
    }

    private fun updateMapOrientation(session: WorkoutSession) {
        if (session.currentLocation != null) {
            val bearing = getDirectionBearing(session.trackPoints)
            if (bearing >= 0) {
                try {
                    val mapOrientation = -bearing
                    mapView?.mapOrientation = mapOrientation
                    android.util.Log.d("MapOrientation", "Updated map orientation: bearing=$bearing, mapOrientation=$mapOrientation")
                } catch (e: Exception) {
                    android.util.Log.e("MapOrientation", "Error updating map orientation: ${e.message}", e)
                }
            }
        }
    }

    private fun centerMapOnCurrentLocation() {
        // Принудительно центрируем карту на текущем местоположении
        isUserInteractingWithMap = false
        lastUserInteractionTime = System.currentTimeMillis()
        lastMapUpdateTime = System.currentTimeMillis() // Сбрасываем таймер обновления

        val session = viewModel.workoutSession.value
        session.currentLocation?.let { location ->
            val geoPoint = GpsFilter.createValidGeoPoint(location)
            if (geoPoint != null) {
                // Используем плавную анимацию
                mapView?.controller?.animateTo(geoPoint, 16.0, 1000L)
                setMapCenteredState(true)
            } else {
                android.util.Log.w("WorkoutTracking", "Invalid GPS coordinates for map center")
                return
            }

            // Обновляем ориентацию карты по направлению движения
            val bearing = getDirectionBearing(session.trackPoints)
            if (bearing >= 0) {
                try {
                    val mapOrientation = -bearing
                    mapView?.mapOrientation = mapOrientation
                    android.util.Log.d("MapCenter", "Centered map and updated orientation: bearing=$bearing, mapOrientation=$mapOrientation")
                } catch (e: Exception) {
                    android.util.Log.e("MapCenter", "Error updating map orientation: ${e.message}", e)
                }
            }
        } ?: run {
            // Если текущее местоположение недоступно, попробуем получить последнее известное
            locationOverlay?.let { overlay ->
                overlay.lastFix?.let { location ->
                    val geoPoint = GpsFilter.createValidGeoPoint(location)
                    if (geoPoint != null) {
                        mapView?.controller?.animateTo(geoPoint, 16.0, 1000L)
                        setMapCenteredState(true)
                    } else {
                        android.util.Log.w("WorkoutTracking", "Invalid GPS coordinates for last fix")
                    }
                }
            }
        }
    }

    private fun requestLocationPermission() {
        when {
            ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED -> {
                initializeMap()
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
                ) == PackageManager.PERMISSION_GRANTED -> {
                    // Разрешение уже предоставлено
                }
                else -> {
                    notificationPermissionRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }
    }

    private fun requestBackgroundLocationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            when {
                ContextCompat.checkSelfPermission(
                    requireContext(),
                    Manifest.permission.ACCESS_BACKGROUND_LOCATION
                ) == PackageManager.PERMISSION_GRANTED -> {
                    // Разрешение на фоновое местоположение уже предоставлено
                }
                else -> {
                    // Запрашиваем разрешение на фоновое местоположение
                    locationPermissionRequest.launch(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
                }
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
                    // Если не удается открыть настройки, показываем уведомление
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.battery_optimization_hint),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun requestActivityRecognitionPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            when {
                ContextCompat.checkSelfPermission(
                    requireContext(),
                    Manifest.permission.ACTIVITY_RECOGNITION
                ) == PackageManager.PERMISSION_GRANTED -> {
                    // Разрешение на распознавание активности уже предоставлено
                }
                else -> {
                    // Запрашиваем разрешение на распознавание физической активности
                    locationPermissionRequest.launch(arrayOf(Manifest.permission.ACTIVITY_RECOGNITION))
                }
            }
        }
    }

    private fun saveWorkoutAndNavigateBack() {
        val session = viewModel.workoutSession.value
        
        if (session.distance > 0 && session.currentTime > 0) {
            // Сохраняем тренировку в базу данных
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val workoutId = viewModel.saveWorkoutToDatabase(selectedWorkoutType)
                    if (workoutId != null) {
                        Toast.makeText(context, String.format(getString(R.string.workout_saved_format), session.distance, viewModel.formatTime(session.currentTime)), Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(context, getString(R.string.workout_save_error), Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(context, getString(R.string.workout_save_error_format, e.message), Toast.LENGTH_SHORT).show()
                }
                
                // Сбрасываем данные тренировки после сохранения
                viewModel.resetWorkout()
                isStoppingWorkout = false
                // Возвращаемся к списку тренировок
                findNavController().navigateUp()
            }
        } else {
            // Если тренировка слишком короткая, не сохраняем
            Toast.makeText(context, getString(R.string.workout_too_short), Toast.LENGTH_SHORT).show()
            // Сбрасываем данные тренировки даже если не сохраняем
            viewModel.resetWorkout()
            isStoppingWorkout = false
            findNavController().navigateUp()
        }
    }

    private fun navigateToWorkoutDetails() {
        val session = viewModel.workoutSession.value
        
        if (session.distance > 0 && session.currentTime > 0) {
            binding.textViewGpsAccuracy.visibility = View.GONE
            binding.buttonStop.isEnabled = false
            
            // Сохраняем тренировку в базу данных
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val workoutId = viewModel.saveWorkoutToDatabase(selectedWorkoutType)
                    if (workoutId != null) {
                        Toast.makeText(context, String.format(getString(R.string.workout_saved_format), session.distance, viewModel.formatTime(session.currentTime)), Toast.LENGTH_LONG).show()
                        
                        // Переходим к экрану деталей тренировки
                        val bundle = Bundle().apply {
                            putLong("workoutId", workoutId)
                        }
                        findNavController().navigate(com.drrhaos.runner.R.id.nav_workout_detail, bundle)
                    } else {
                        com.drrhaos.runner.util.ErrorHandler.handleSaveError(requireContext(), Exception("Failed to save workout"))
                    }
                } catch (e: Exception) {
                    com.drrhaos.runner.util.ErrorHandler.handleSaveError(requireContext(), e)
                } finally {
                    // Скрываем индикатор загрузки
                    binding.textViewGpsAccuracy.visibility = View.GONE
                    binding.buttonStop.isEnabled = true
                    
                    // Сбрасываем данные тренировки после сохранения
                    viewModel.resetWorkout()
                    isStoppingWorkout = false
                }
            }
        } else {
            Toast.makeText(context, getString(R.string.no_data_to_save), Toast.LENGTH_SHORT).show()
            isStoppingWorkout = false
        }
    }
    private fun initTTS() {
        tts = TextToSpeech(requireContext(), object: TextToSpeech.OnInitListener {
            override fun onInit(status: Int) {
                if (status == TextToSpeech.SUCCESS) {
                    val locale = requireContext().resources.configuration.locales[0]
                    when (tts?.isLanguageAvailable(locale)) {
                        TextToSpeech.LANG_AVAILABLE,
                        TextToSpeech.LANG_COUNTRY_AVAILABLE,
                        TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE -> {
                            Log.d(TAG, "TTS onInit: setting language ${locale.language}")
                            tts?.language = locale
                        }
                        // errors
                        TextToSpeech.LANG_MISSING_DATA -> {
                            Log.d(TAG, "TTS onInit: missing language data")
                            destroyTTS()
                        }
                        TextToSpeech.LANG_NOT_SUPPORTED -> {
                            Log.d(TAG, "TTS onInit: language unsupported")
                            destroyTTS()
                        }
                    }
                } else {
                    Log.d(TAG, "TTS onInit: failure")
                    destroyTTS()
                }
            }
        })
    }
    private fun destroyTTS() {
        val _tts = tts
        if (_tts != null) {
            _tts.stop()
            _tts.shutdown()
            tts = null
        }
    }

    override fun onResume() {
        super.onResume()
        mapView?.onResume()
    }

    override fun onPause() {
        super.onPause()
        mapView?.onPause()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        
        // Отменяем все запланированные задачи автопоиска
        mapView?.handler?.removeCallbacks(autoCenterRunnable)
        
        // Останавливаем периодическое обновление GPS статуса
        gpsStatusHandler.removeCallbacks(gpsStatusRunnable)
        
        // Останавливаем мониторинг GPS статуса
        try {
            locationManager.removeUpdates(gpsStatusListener)
            android.util.Log.d("GPSStatus", "GPS status monitoring stopped")
        } catch (e: Exception) {
            android.util.Log.e("GPSStatus", "Failed to stop GPS status monitoring: ${e.message}")
        }

        cancelStopHold()

        // Отключаемся от сервиса если подключены (тренировка в foreground-сервисе продолжается)
        try {
            viewModel.cleanup()
        } catch (e: Exception) {
            // ViewModel может быть не инициализирован
        }
        destroyTTS()
        // Очищаем карту
        mapView?.onDetach()
        mapView = null
        locationOverlay = null
        trackPolyline = null
        
        _binding = null
    }
}
