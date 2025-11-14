package com.example.runner.ui.tracking

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.location.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.osmdroid.util.GeoPoint
import java.util.*
import com.example.runner.data.Workout
import com.example.runner.data.WorkoutType
import com.example.runner.data.WorkoutDao
import com.example.runner.data.TrackPoint
import com.example.runner.data.TrackData
import androidx.lifecycle.ViewModelProvider
import com.google.gson.Gson
import com.example.runner.service.WorkoutTrackingService
import android.content.Intent
import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import com.example.runner.util.GpsFilter

data class WorkoutSession(
    val isTracking: Boolean = false,
    val isPaused: Boolean = false,
    val startTime: Long = 0,
    val pauseTime: Long = 0,
    val totalPauseDuration: Long = 0,
    val currentTime: Long = 0,
    val distance: Float = 0f,
    val avgPace: Float = 0f, // средний темп в минутах на километр
    val currentPace: Float = 0f, // текущий темп в минутах на километр
    val avgSpeed: Float = 0f,
    val currentSpeed: Float = 0f,
    val heartRate: Int = 0,
    val calories: Int = 0,
    val gpsStatus: GpsStatus = GpsStatus.SEARCHING,
    val trackPoints: List<GeoPoint> = emptyList(), // для отображения на карте
    val trackDataPoints: List<TrackPoint> = emptyList(), // для сохранения в JSON
    val rawTrackDataPoints: List<TrackPoint> = emptyList(), // все точки без фильтрации для последующей проверки
    val currentLocation: Location? = null
)

enum class GpsStatus {
    SEARCHING,
    WEAK,
    MEDIUM,
    STRONG,
    FOUND,
    LOST,
    DENIED
}

enum class WorkoutState {
    NOT_STARTED,    // 1. не запущена
    RUNNING,        // 2. запущена  
    PAUSED,         // 3. пауза
    STOPPED         // 4. остановлена
}

class WorkoutTrackingViewModel(private val workoutDao: WorkoutDao, private val context: Context) : ViewModel() {

    private val _workoutSession = MutableStateFlow(WorkoutSession())
    val workoutSession: StateFlow<WorkoutSession> = _workoutSession.asStateFlow()

    private val _workoutState = MutableStateFlow(WorkoutState.NOT_STARTED)
    val workoutState: StateFlow<WorkoutState> = _workoutState.asStateFlow()

    private var fusedLocationClient: FusedLocationProviderClient? = null
    private var locationCallback: LocationCallback? = null
    private var lastLocation: Location? = null
    private var lastUpdateTime: Long = 0
    private var isCurrentlyTracking: Boolean = false // отслеживаем только активное состояние

    private val timer = Timer()
    private val gson = Gson()
    
    // Работа с сервисом
    private var trackingService: WorkoutTrackingService? = null
    private var isServiceBound = false
    
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            android.util.Log.d("WorkoutTrackingViewModel", "Service connected")
            val binder = service as WorkoutTrackingService.WorkoutTrackingBinder
            trackingService = binder.getService()
            isServiceBound = true
            // Отключаем локальные обновления местоположения, если они были запущены ранее
            try {
                locationCallback?.let { cb -> fusedLocationClient?.removeLocationUpdates(cb) }
            } catch (_: Exception) { }
            
            // Подписываемся на обновления сессии
            trackingService?.setSessionUpdateCallback { session ->
                android.util.Log.d("WorkoutTrackingViewModel", "Session updated from service: isTracking=${session.isTracking}, isPaused=${session.isPaused}")
                _workoutSession.value = session
                updateWorkoutState()
            }
            
            android.util.Log.d("WorkoutTrackingViewModel", "Service fully initialized and ready")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            android.util.Log.d("WorkoutTrackingViewModel", "Service disconnected")
            trackingService = null
            isServiceBound = false
        }
    }

    fun initializeLocationClient(context: Context) {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
        setupLocationCallback()
        // Локальные обновления запускаются только как fallback, сервис — основной источник
    }

    fun initializeService() {
        android.util.Log.d("WorkoutTrackingViewModel", "initializeService called")
        val intent = Intent(context, WorkoutTrackingService::class.java)
        val result = context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        android.util.Log.d("WorkoutTrackingViewModel", "Service bind result: $result")
    }

    private fun setupLocationCallback() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    updateLocation(location)
                }
            }
        }
    }

    private fun startLocationUpdates(context: Context) {
        // Если сервис привязан, используем его обновления и не дублируем запросы
        if (isServiceBound) {
            android.util.Log.d("WorkoutTrackingViewModel", "Skipping local location updates (service bound)")
            return
        }
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            _workoutSession.value = _workoutSession.value.copy(gpsStatus = GpsStatus.DENIED)
            return
        }

        val locationRequest = com.example.runner.util.GpsConfig.createWorkoutLocationRequest()

        fusedLocationClient?.requestLocationUpdates(
            locationRequest,
            locationCallback!!,
            context.mainLooper
        )
    }

    private fun updateLocation(location: Location) {
        val currentSession = _workoutSession.value
        val newTrackPoints = currentSession.trackPoints.toMutableList()
        val newTrackDataPoints = currentSession.trackDataPoints.toMutableList()
        val newRawTrackDataPoints = currentSession.rawTrackDataPoints.toMutableList()
        
        // Записываем данные только во время активного трекинга (не во время паузы)
        if (isCurrentlyTracking && !currentSession.isPaused) {
            // Добавляем новую точку к треку для отображения на карте только во время активного трекинга
            newTrackPoints.add(GeoPoint(location.latitude, location.longitude))
            
            val trackPoint = TrackPoint(
                latitude = location.latitude,
                longitude = location.longitude,
                timestamp = System.currentTimeMillis(),
                accuracy = location.accuracy,
                speed = location.speed,
                altitude = location.altitude
            )
            newTrackDataPoints.add(trackPoint)
            newRawTrackDataPoints.add(trackPoint)

            // Вычисляем дистанцию только во время активного трекинга
            var newDistance = currentSession.distance
            lastLocation?.let { lastLoc ->
                val segmentDistance = location.distanceTo(lastLoc)
                newDistance += segmentDistance / 1000f // конвертируем в километры
            }

            // Вычисляем скорость и темп только во время активного трекинга
            val currentTime = System.currentTimeMillis()
            val timeDiff = if (lastUpdateTime > 0) currentTime - lastUpdateTime else 0
            
            // Текущая скорость (км/ч)
            val currentSpeed = if (timeDiff > 0 && lastLocation != null) {
                val distanceDiff = location.distanceTo(lastLocation!!) / 1000f // км
                val timeDiffHours = timeDiff / (1000f * 3600f) // часы
                if (timeDiffHours > 0) distanceDiff / timeDiffHours else 0f
            } else 0f

            // Средняя скорость (км/ч)
            val avgSpeed = if (currentSession.currentTime > 0 && newDistance > 0) {
                val activeTimeHours = currentSession.currentTime / (1000f * 3600f) // часы активного времени
                if (activeTimeHours > 0) newDistance / activeTimeHours else 0f
            } else 0f

            // Текущий темп (минуты на километр)
            val currentPace = if (currentSpeed > 0) {
                60f / currentSpeed // минуты на километр
            } else 0f

            // Средний темп (минуты на километр)
            val avgPace = if (avgSpeed > 0) {
                60f / avgSpeed // минуты на километр
            } else 0f

            // Вычисляем калории с учетом веса пользователя
            val userPrefs = com.example.runner.util.UserPreferences(context)
            val calories = com.example.runner.util.FormatUtils.calculateCalories(newDistance, userPrefs.userWeight)

            _workoutSession.value = currentSession.copy(
                currentLocation = location,
                trackPoints = newTrackPoints,
                trackDataPoints = newTrackDataPoints,
                rawTrackDataPoints = newRawTrackDataPoints,
                distance = newDistance,
                currentSpeed = currentSpeed,
                avgSpeed = avgSpeed,
                currentPace = currentPace,
                avgPace = avgPace,
                calories = calories,
                gpsStatus = GpsStatus.FOUND
            )

            lastLocation = location
            lastUpdateTime = currentTime
        } else {
            // Во время паузы обновляем только статус GPS и текущее местоположение
            // НЕ добавляем точки к треку для отображения на карте
            _workoutSession.value = currentSession.copy(
                currentLocation = location,
                rawTrackDataPoints = newRawTrackDataPoints,
                gpsStatus = GpsStatus.FOUND
            )
        }
    }

    fun startWorkout(workoutType: WorkoutType = WorkoutType.EASY_RUN) {
        android.util.Log.d("WorkoutTrackingViewModel", "startWorkout called, isServiceBound: $isServiceBound")
        
        // Проверяем разрешения GPS перед запуском
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            android.util.Log.e("WorkoutTrackingViewModel", "GPS permission not granted, cannot start workout")
            _workoutSession.value = _workoutSession.value.copy(gpsStatus = GpsStatus.DENIED)
            return
        }
        
        if (isServiceBound && trackingService != null) {
            android.util.Log.d("WorkoutTrackingViewModel", "Starting via service")
            val intent = Intent(context, WorkoutTrackingService::class.java).apply {
                action = WorkoutTrackingService.ACTION_START_WORKOUT
                putExtra(WorkoutTrackingService.EXTRA_WORKOUT_TYPE, workoutType)
            }
            context.startForegroundService(intent)
        } else {
            // Fallback к локальному трекингу если сервис недоступен
            android.util.Log.w("WorkoutTrackingViewModel", "Service not bound, starting locally. isServiceBound: $isServiceBound, trackingService: ${trackingService != null}")
            val currentTime = System.currentTimeMillis()
            isCurrentlyTracking = true
            _workoutSession.value = _workoutSession.value.copy(
                isTracking = true,
                startTime = currentTime,
                pauseTime = 0,
                totalPauseDuration = 0
            )
            _workoutState.value = WorkoutState.RUNNING
            startTimer()
            android.util.Log.d("WorkoutTrackingViewModel", "State set to TRACKING")
        }
    }

    fun startWorkoutWithRetry(workoutType: WorkoutType = WorkoutType.EASY_RUN, maxRetries: Int = 3) {
        var retryCount = 0
        
        fun attemptStart() {
            if (isServiceBound && trackingService != null) {
                startWorkout(workoutType)
            } else if (retryCount < maxRetries) {
                retryCount++
                android.util.Log.d("WorkoutTrackingViewModel", "Service not ready, retrying in 500ms (attempt $retryCount/$maxRetries)")
                viewModelScope.launch {
                    kotlinx.coroutines.delay(500)
                    attemptStart()
                }
            } else {
                android.util.Log.w("WorkoutTrackingViewModel", "Max retries reached, starting locally")
                startWorkout(workoutType)
            }
        }
        
        attemptStart()
    }

    fun pauseWorkout() {
        android.util.Log.d("WorkoutTrackingViewModel", "pauseWorkout called, isServiceBound: $isServiceBound")
        
        if (isServiceBound && trackingService != null) {
            android.util.Log.d("WorkoutTrackingViewModel", "Pausing via service")
            val intent = Intent(context, WorkoutTrackingService::class.java).apply {
                action = WorkoutTrackingService.ACTION_PAUSE_WORKOUT
            }
            context.startService(intent)
        } else {
            // Fallback к локальному трекингу
            android.util.Log.d("WorkoutTrackingViewModel", "Pausing locally")
            val currentTime = System.currentTimeMillis()
            isCurrentlyTracking = false
            _workoutSession.value = _workoutSession.value.copy(
                isTracking = true, // Тренировка продолжается, но на паузе
                isPaused = true,
                pauseTime = currentTime
            )
            _workoutState.value = WorkoutState.PAUSED
            android.util.Log.d("WorkoutTrackingViewModel", "State set to PAUSED")
        }
    }

    fun resumeWorkout() {
        android.util.Log.d("WorkoutTrackingViewModel", "resumeWorkout called, isServiceBound: $isServiceBound")
        
        if (isServiceBound && trackingService != null) {
            android.util.Log.d("WorkoutTrackingViewModel", "Resuming via service")
            val intent = Intent(context, WorkoutTrackingService::class.java).apply {
                action = WorkoutTrackingService.ACTION_RESUME_WORKOUT
            }
            context.startService(intent)
        } else {
            // Fallback к локальному трекингу
            android.util.Log.d("WorkoutTrackingViewModel", "Resuming locally")
            val currentTime = System.currentTimeMillis()
            val currentSession = _workoutSession.value
            val pauseDuration = currentTime - currentSession.pauseTime
            isCurrentlyTracking = true
            _workoutSession.value = currentSession.copy(
                isTracking = true, // Тренировка возобновлена
                isPaused = false,
                pauseTime = 0,
                totalPauseDuration = currentSession.totalPauseDuration + pauseDuration
            )
            _workoutState.value = WorkoutState.RUNNING
            android.util.Log.d("WorkoutTrackingViewModel", "State set to TRACKING")
        }
    }

    fun stopWorkout() {
        android.util.Log.d("WorkoutTrackingViewModel", "stopWorkout called, isServiceBound: $isServiceBound")
        
        if (isServiceBound && trackingService != null) {
            android.util.Log.d("WorkoutTrackingViewModel", "Stopping via service")
            val intent = Intent(context, WorkoutTrackingService::class.java).apply {
                action = WorkoutTrackingService.ACTION_STOP_WORKOUT
            }
            context.startService(intent)
            // Отключаемся от сервиса после остановки тренировки
            context.unbindService(serviceConnection)
            isServiceBound = false
            trackingService = null
        } else {
            // Fallback к локальному трекингу
            android.util.Log.d("WorkoutTrackingViewModel", "Stopping locally")
            isCurrentlyTracking = false
            _workoutSession.value = _workoutSession.value.copy(
                isTracking = false,
                isPaused = false
            )
            _workoutState.value = WorkoutState.STOPPED
            stopLocationUpdates()
            timer.cancel()
        }
    }

    fun resetWorkout() {
        isCurrentlyTracking = false
        lastLocation = null
        lastUpdateTime = 0
        _workoutSession.value = WorkoutSession()
        _workoutState.value = WorkoutState.NOT_STARTED
        
        // Останавливаем сервис если он запущен
        if (isServiceBound) {
            context.unbindService(serviceConnection)
            isServiceBound = false
            trackingService = null
        }
    }

    private fun updateWorkoutState() {
        val session = _workoutSession.value
        val oldState = _workoutState.value
        val newState = when {
            !session.isTracking && !session.isPaused -> WorkoutState.NOT_STARTED
            session.isTracking && !session.isPaused -> WorkoutState.RUNNING
            session.isTracking && session.isPaused -> WorkoutState.PAUSED
            !session.isTracking && session.isPaused -> WorkoutState.PAUSED // Сохраняем паузу даже если isTracking false
            else -> {
                android.util.Log.w("WorkoutTrackingViewModel", "Unexpected state combination: isTracking=${session.isTracking}, isPaused=${session.isPaused}")
                WorkoutState.NOT_STARTED // Fallback к NOT_STARTED
            }
        }
        
        if (oldState != newState) {
            android.util.Log.d("WorkoutTrackingViewModel", "State changed: $oldState -> $newState (isTracking: ${session.isTracking}, isPaused: ${session.isPaused})")
        }
        
        _workoutState.value = newState
    }

    private fun startTimer() {
        android.util.Log.d("WorkoutTrackingViewModel", "Starting timer")
        timer.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                val session = _workoutSession.value
                android.util.Log.d("WorkoutTrackingViewModel", "Timer tick: isTracking=${session.isTracking}, isPaused=${session.isPaused}, startTime=${session.startTime}")
                
                if (session.isTracking && !session.isPaused) {
                    val currentTime = System.currentTimeMillis()
                    val elapsedTime = currentTime - session.startTime - session.totalPauseDuration
                    android.util.Log.d("WorkoutTrackingViewModel", "Updating time: elapsedTime=$elapsedTime")
                    _workoutSession.value = session.copy(currentTime = elapsedTime)
                }
            }
        }, 0, 1000)
    }

    private fun stopLocationUpdates() {
        locationCallback?.let { callback ->
            fusedLocationClient?.removeLocationUpdates(callback)
        }
    }

    fun formatTime(milliseconds: Long): String {
        return com.example.runner.util.FormatUtils.formatTime(milliseconds)
    }

    fun formatSpeed(speedKmh: Float): String {
        return com.example.runner.util.FormatUtils.formatSpeed(speedKmh)
    }

    fun formatPace(paceMinutesPerKm: Float): String {
        return com.example.runner.util.FormatUtils.formatPace(paceMinutesPerKm)
    }

    suspend fun saveWorkoutToDatabase(workoutType: WorkoutType = WorkoutType.EASY_RUN): Long? {
        val session = _workoutSession.value
        
        // Валидация данных перед сохранением
        if (session.distance <= 0 || session.currentTime <= 0) {
            return null
        }

        val sourcePoints = if (session.rawTrackDataPoints.isNotEmpty()) {
            session.rawTrackDataPoints
        } else {
            session.trackDataPoints
        }
        val sanitizedPoints = sanitizeTrackPoints(sourcePoints)
        if (sanitizedPoints.size < 2) {
            android.util.Log.w("WorkoutTracking", "Not enough sanitized GPS points to save workout")
            return null
        }

        val totalDistanceMeters = calculateTotalDistanceMeters(sanitizedPoints)
        if (totalDistanceMeters <= 0f) {
            android.util.Log.w("WorkoutTracking", "Total distance after sanitization is zero")
            return null
        }

        val totalDistanceKm = totalDistanceMeters / 1000f
        val durationMs = session.currentTime
        val avgSpeedMps = if (durationMs > 0) {
            totalDistanceMeters / (durationMs / 1000f)
        } else 0f
        val avgPace = if (totalDistanceKm > 0f) {
            (durationMs / 60000f) / totalDistanceKm
        } else 0f
        val maxSpeedMps = sanitizedPoints.maxOfOrNull { it.speed ?: 0f } ?: 0f

        val userPrefs = com.example.runner.util.UserPreferences(context)
        val calories = com.example.runner.util.FormatUtils.calculateCalories(totalDistanceKm, userPrefs.userWeight)

        // Создаем JSON с данными траектории
        val trackData = TrackData(
            points = sanitizedPoints,
            totalDistance = totalDistanceMeters,
            totalDuration = durationMs,
            avgSpeed = avgSpeedMps,
            maxSpeed = maxSpeedMps,
            startTime = session.startTime,
            endTime = System.currentTimeMillis()
        )

        val trackDataJson = gson.toJson(trackData)
        
        // Логируем информацию о сохраняемом треке
        android.util.Log.d("WorkoutTracking", "Saving track with ${session.trackDataPoints.size} points")
        android.util.Log.d("WorkoutTracking", "Track data JSON length: ${trackDataJson.length}")

        val workout = Workout(
            date = Date(session.startTime),
            distance = totalDistanceKm,
            duration = durationMs,
            avgPace = avgPace,
            calories = calories,
            notes = null, // Можно добавить возможность ввода заметок
            type = workoutType,
            trackData = trackDataJson
        )

        return try {
            // Используем retry механизм для надежного сохранения
            val workoutId = com.example.runner.util.ErrorHandler.retryWithBackoff(
                maxRetries = 3,
                initialDelay = 1000L
            ) {
                workoutDao.insertWorkout(workout)
            }
            android.util.Log.d("WorkoutTracking", "Workout saved successfully with ID: $workoutId")
            workoutId
        } catch (e: Exception) {
            android.util.Log.e("WorkoutTracking", "Error saving workout after retries: ${e.message}", e)
            com.example.runner.util.ErrorHandler.handleSaveError(context, e, false)
            null
        }
    }

    fun getWorkoutSummary(): String {
        val session = _workoutSession.value
        return String.format(
            "Дистанция: %.2f км\nВремя: %s\nСредний темп: %s\nКалории: %d",
            session.distance,
            formatTime(session.currentTime),
            formatPace(session.avgPace),
            session.calories
        )
    }

    private fun sanitizeTrackPoints(rawPoints: List<TrackPoint>): List<TrackPoint> {
        if (rawPoints.isEmpty()) return emptyList()
        val result = mutableListOf<TrackPoint>()
        var previousLocation: Location? = null

        for (point in rawPoints) {
            val rawLocation = trackPointToLocation(point)
            val filteredLocation = GpsFilter.filterGpsOutlier(rawLocation, previousLocation)
            if (filteredLocation != null) {
                if (previousLocation != null) {
                    val segmentDistance = filteredLocation.distanceTo(previousLocation)
                    if (segmentDistance < WorkoutTrackingService.MIN_POINT_DISTANCE_METERS) {
                        continue
                    }
                }

                result.add(
                    point.copy(
                        latitude = filteredLocation.latitude,
                        longitude = filteredLocation.longitude,
                        accuracy = filteredLocation.accuracy,
                        speed = filteredLocation.speed,
                        altitude = filteredLocation.altitude
                    )
                )
                previousLocation = filteredLocation
            }
        }

        return result
    }

    private fun calculateTotalDistanceMeters(points: List<TrackPoint>): Float {
        if (points.size < 2) return 0f
        var distance = 0f
        var previousLocation: Location? = null

        for (point in points) {
            val location = trackPointToLocation(point)
            previousLocation?.let { prev ->
                distance += location.distanceTo(prev)
            }
            previousLocation = location
        }

        return distance
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

    fun cleanup() {
        stopLocationUpdates()
        timer.cancel()
        
        // Отключаемся от сервиса
        if (isServiceBound) {
            context.unbindService(serviceConnection)
            isServiceBound = false
        }
    }

    override fun onCleared() {
        super.onCleared()
        cleanup()
    }
}

class WorkoutTrackingViewModelFactory(
    private val workoutDao: WorkoutDao,
    private val context: Context
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(WorkoutTrackingViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return WorkoutTrackingViewModel(workoutDao, context) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
