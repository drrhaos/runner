package com.runner.academy.ui.tracking

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.location.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.osmdroid.util.GeoPoint
import com.runner.academy.data.Workout
import com.runner.academy.data.WorkoutType
import com.runner.academy.data.WorkoutRepository
import com.runner.academy.data.TrackPoint
import com.runner.academy.data.TrackData
import com.runner.academy.data.WorkoutSession
import com.runner.academy.data.GpsStatus
import com.runner.academy.data.WorkoutState
import com.runner.academy.data.LocationSource
import androidx.lifecycle.ViewModelProvider
import com.google.gson.Gson
import com.runner.academy.service.WorkoutTrackingService
import android.content.Intent
import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import com.runner.academy.util.GpsFilter
import com.runner.academy.util.SpeedPaceCalculator
import com.runner.academy.util.UserPreferences
import java.util.Date

class WorkoutTrackingViewModel(private val repository: WorkoutRepository, private val application: Application) : ViewModel() {

    private val _workoutSession = MutableStateFlow(WorkoutSession())
    val workoutSession: StateFlow<WorkoutSession> = _workoutSession.asStateFlow()

    private val _workoutState = MutableStateFlow(WorkoutState.NOT_STARTED)
    val workoutState: StateFlow<WorkoutState> = _workoutState.asStateFlow()

    private var fusedLocationClient: FusedLocationProviderClient? = null
    private var locationCallback: LocationCallback? = null
    private var lastLocation: Location? = null
    private var lastUpdateTime: Long = 0
    private var isCurrentlyTracking: Boolean = false // отслеживаем только активное состояние

    private var workoutTimerJob: Job? = null
    private val gson = Gson()
    
    // Работа с сервисом
    private var trackingService: WorkoutTrackingService? = null
    private var isServiceBound = false
    
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as WorkoutTrackingService.WorkoutTrackingBinder
            trackingService = binder.getService()
            isServiceBound = true
            // Отключаем локальные обновления местоположения, если они были запущены ранее
            try {
                locationCallback?.let { cb -> fusedLocationClient?.removeLocationUpdates(cb) }
            } catch (_: Exception) { }
            
            // Подписываемся на обновления сессии
            trackingService?.setSessionUpdateCallback { session ->
                _workoutSession.value = session
                updateWorkoutState()
            }

            // Сразу подтягиваем состояние из сервиса (после пересоздания UI / переподключения)
            trackingService?.let { svc ->
                _workoutSession.value = svc.getCurrentSession()
                updateWorkoutState()
            }

        }

        override fun onServiceDisconnected(name: ComponentName?) {
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
        if (isServiceBound) {
            return
        }
        val intent = Intent(application, WorkoutTrackingService::class.java)
        application.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
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
            return
        }
        if (!hasLocationPermission()) {
            _workoutSession.value = _workoutSession.value.copy(gpsStatus = GpsStatus.DENIED)
            return
        }

        val locationRequest = com.runner.academy.util.GpsConfig.createWorkoutLocationRequest()

        try {
            fusedLocationClient?.requestLocationUpdates(
                locationRequest,
                locationCallback!!,
                android.os.Looper.getMainLooper()
            )
        } catch (e: SecurityException) {
            android.util.Log.w("WorkoutTrackingViewModel", "Location permission denied at runtime", e)
            _workoutSession.value = _workoutSession.value.copy(gpsStatus = GpsStatus.DENIED)
        }
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

            // Delegate distance calculation to SpeedPaceCalculator (point-to-point Haversine)
            val totalDistanceMeters = SpeedPaceCalculator.totalDistanceMeters(newTrackDataPoints)
            val newDistance = totalDistanceMeters / 1000f

            // Derived speed between consecutive points instead of noisy GPS-reported speed
            val currentSpeed = if (newTrackDataPoints.size >= 2) {
                val prevPoint = newTrackDataPoints[newTrackDataPoints.size - 2]
                val derivedSpeedMs = SpeedPaceCalculator.derivedSpeedMs(prevPoint, trackPoint)
                SpeedPaceCalculator.speedMsToKmh(derivedSpeedMs)
            } else {
                0f
            }

            // Average speed from centralized calculator
            val avgSpeedMs = SpeedPaceCalculator.averageSpeedMs(totalDistanceMeters, currentSession.currentTime)
            val avgSpeed = SpeedPaceCalculator.speedMsToKmh(avgSpeedMs)

            // Текущий темп (минуты на километр)
            val currentPace = SpeedPaceCalculator.computePaceRaw(currentSpeed)

            // Средний темп (минуты на километр)
            val avgPace = SpeedPaceCalculator.computePaceRaw(avgSpeed)

            // Вычисляем калории с учетом веса пользователя
            val userPrefs = com.runner.academy.util.UserPreferences(application)
            val calories = com.runner.academy.util.FormatUtils.calculateCalories(newDistance, userPrefs.userWeight)

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
            lastUpdateTime = System.currentTimeMillis()
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
        android.util.Log.d("WorkoutTrackingViewModel", "startWorkout called, isServiceBound: $isServiceBound")
        val gpsStatus = when {
            !hasLocationPermission() -> GpsStatus.DENIED
            else -> GpsStatus.SEARCHING
        }

        if (isServiceBound && trackingService != null) {
            val intent = Intent(application, WorkoutTrackingService::class.java).apply {
                action = WorkoutTrackingService.ACTION_START_WORKOUT
                putExtra(WorkoutTrackingService.EXTRA_WORKOUT_TYPE, workoutType)
            }
            application.startForegroundService(intent)
        } else {
            android.util.Log.w(
                "WorkoutTrackingViewModel",
                "Service not bound, starting locally. isServiceBound: $isServiceBound, trackingService: ${trackingService != null}"
            )
            val currentTime = System.currentTimeMillis()
            isCurrentlyTracking = true
            _workoutSession.value = _workoutSession.value.copy(
                isTracking = true,
                isPaused = false,
                startTime = currentTime,
                pauseTime = 0,
                totalPauseDuration = 0,
                gpsStatus = gpsStatus
            )
            _workoutState.value = WorkoutState.RUNNING
            startTimer()
            if (hasLocationPermission()) {
                startLocationUpdates(application)
            }
        }
    }

    fun startWorkoutWithRetry(workoutType: WorkoutType = WorkoutType.EASY_RUN, maxRetries: Int = 3) {
        var retryCount = 0

        fun attemptStart() {
            if (isServiceBound && trackingService != null) {
                startWorkout(workoutType)
            } else if (retryCount < maxRetries) {
                retryCount++
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
        if (isServiceBound && trackingService != null) {
            val intent = Intent(application, WorkoutTrackingService::class.java).apply {
                action = WorkoutTrackingService.ACTION_PAUSE_WORKOUT
            }
            application.startService(intent)
        } else {
            // Fallback к локальному трекингу
            val currentTime = System.currentTimeMillis()
            isCurrentlyTracking = false
            _workoutSession.value = _workoutSession.value.copy(
                isTracking = true, // Тренировка продолжается, но на паузе
                isPaused = true,
                pauseTime = currentTime
            )
            _workoutState.value = WorkoutState.PAUSED
        }
    }

    fun resumeWorkout() {
        if (isServiceBound && trackingService != null) {
            val intent = Intent(application, WorkoutTrackingService::class.java).apply {
                action = WorkoutTrackingService.ACTION_RESUME_WORKOUT
            }
            application.startService(intent)
        } else {
            // Fallback к локальному трекингу
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
        }
    }

    fun stopWorkout() {
        if (isServiceBound && trackingService != null) {
            val intent = Intent(application, WorkoutTrackingService::class.java).apply {
                action = WorkoutTrackingService.ACTION_STOP_WORKOUT
            }
            application.startService(intent)
            // Отключаемся от сервиса после остановки тренировки
            application.unbindService(serviceConnection)
            isServiceBound = false
            trackingService = null
        } else {
            // Fallback к локальному трекингу
            isCurrentlyTracking = false
            _workoutSession.value = _workoutSession.value.copy(
                isTracking = false,
                isPaused = false
            )
            _workoutState.value = WorkoutState.STOPPED
            stopLocationUpdates()
            stopLocalWorkoutTimer()
        }
    }

    fun resetWorkout() {
        isCurrentlyTracking = false
        lastLocation = null
        lastUpdateTime = 0
        stopLocalWorkoutTimer()

        if (isServiceBound) {
            try {
                application.unbindService(serviceConnection)
            } catch (_: Exception) { }
            isServiceBound = false
            trackingService = null
        }

        try {
            val stopIntent = Intent(application, WorkoutTrackingService::class.java).apply {
                action = WorkoutTrackingService.ACTION_STOP_WORKOUT
            }
            application.startService(stopIntent)
        } catch (_: Exception) { }

        _workoutSession.value = WorkoutSession()
        _workoutState.value = WorkoutState.NOT_STARTED
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
            _workoutState.value = newState
        }
    }

    private fun startTimer() {
        stopLocalWorkoutTimer()
        workoutTimerJob = viewModelScope.launch {
            while (isActive) {
                val session = _workoutSession.value
                if (session.isTracking && !session.isPaused) {
                    delay(1000)
                    val currentTime = System.currentTimeMillis()
                    val elapsedTime = currentTime - session.startTime - session.totalPauseDuration
                    _workoutSession.value = session.copy(currentTime = elapsedTime)
                } else {
                    break
                }
            }
        }
    }

    private fun stopLocalWorkoutTimer() {
        workoutTimerJob?.cancel()
        workoutTimerJob = null
    }

    private fun stopLocationUpdates() {
        locationCallback?.let { callback ->
            fusedLocationClient?.removeLocationUpdates(callback)
        }
    }

    fun formatTime(milliseconds: Long): String {
        return com.runner.academy.util.FormatUtils.formatTime(milliseconds)
    }

    fun formatSpeed(speedKmh: Float): String {
        return com.runner.academy.util.FormatUtils.formatSpeed(speedKmh)
    }

    fun formatPace(paceMinutesPerKm: Float): String {
        return com.runner.academy.util.FormatUtils.formatPace(paceMinutesPerKm)
    }

    suspend fun saveWorkoutToDatabase(
        workoutType: WorkoutType = WorkoutType.EASY_RUN,
        manualDistanceKm: Float? = null,
        intervalSegmentsJson: String? = null
    ): Long? {
        val session = _workoutSession.value

        if (session.currentTime <= 0) {
            return null
        }

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
            android.util.Log.w("WorkoutTracking", "No distance and no duration to save")
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

        val userPrefs = UserPreferences(application)
        val calories = com.runner.academy.util.FormatUtils.calculateCalories(totalDistanceKm, userPrefs.userWeight)

        val trackDataJson = if (hasTrack && manualDistanceKm == null && totalDistanceMeters > 0f) {
            val trackData = TrackData(
                points = sanitizedPoints,
                totalDistance = totalDistanceMeters,
                totalDuration = durationMs,
                avgSpeed = avgSpeedMps,
                maxSpeed = maxSpeedMps,
                startTime = session.startTime,
                endTime = System.currentTimeMillis()
            )
            gson.toJson(trackData)
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
            // Используем retry механизм для надежного сохранения
            val workoutId = com.runner.academy.util.ErrorHandler.retryWithBackoff(
                maxRetries = 3,
                initialDelay = 1000L
            ) {
                repository.insertWorkout(workout)
            }
            workoutId
        } catch (e: Exception) {
            android.util.Log.e("WorkoutTracking", "Error saving workout after retries: ${e.message}", e)
            com.runner.academy.util.ErrorHandler.handleSaveError(application, e, false)
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
            val forceGap = GpsFilter.isGapResume(previousLocation, rawLocation) || point.afterGap
            val filteredLocation = GpsFilter.filterGpsOutlier(
                rawLocation,
                previousLocation,
                forceGapResume = forceGap
            )
            if (filteredLocation != null) {
                val afterGap = forceGap && previousLocation != null
                if (!afterGap && previousLocation != null) {
                    val segmentDistance = filteredLocation.distanceTo(previousLocation)
                    if (segmentDistance < com.runner.academy.service.GpsLocationProcessor.MIN_POINT_DISTANCE_METERS) {
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

    fun cleanup() {
        stopLocationUpdates()
        stopLocalWorkoutTimer()
        
        // Отключаемся от сервиса
        if (isServiceBound) {
            application.unbindService(serviceConnection)
            isServiceBound = false
        }
    }

    override fun onCleared() {
        super.onCleared()
        cleanup()
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
