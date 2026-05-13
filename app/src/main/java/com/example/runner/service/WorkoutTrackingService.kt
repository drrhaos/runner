package com.example.runner.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.runner.MainActivity
import com.example.runner.R
import com.example.runner.data.TrackPoint
import com.example.runner.data.WorkoutType
import com.example.runner.data.maxReasonableGpsSpeedMps
import com.example.runner.ui.tracking.WorkoutSession
import com.example.runner.util.GpsFilter
import com.example.runner.util.GpsConfig
import com.example.runner.util.FormatUtils
import com.example.runner.util.UserPreferences
import com.google.android.gms.location.*
import org.osmdroid.util.GeoPoint
import java.util.*

class WorkoutTrackingService : Service() {
    
    companion object {
        const val CHANNEL_ID = "WorkoutTrackingChannel"
        const val NOTIFICATION_ID = 1
        const val ACTION_START_WORKOUT = "START_WORKOUT"
        const val ACTION_PAUSE_WORKOUT = "PAUSE_WORKOUT"
        const val ACTION_RESUME_WORKOUT = "RESUME_WORKOUT"
        const val ACTION_STOP_WORKOUT = "STOP_WORKOUT"
        const val EXTRA_WORKOUT_TYPE = "WORKOUT_TYPE"
        const val MIN_POINT_DISTANCE_METERS = 2f
        const val NO_LOCATION_UPDATE_TIMEOUT_MS = 5000L
        const val PERIODIC_LOCATION_REQUEST_INTERVAL_MS = 2000L
        const val WORKOUT_TIMER_INTERVAL_MS = 1000L
        /** Лимит точек карты / отфильтрованного трека в RAM при длительных тренировках */
        const val MAX_TRACK_POINTS_DISPLAY = 8000
        /** Лимит сырых точек (все приходящие от Fused) */
        const val MAX_RAW_TRACK_POINTS = 15000
        // Интервал продления wake lock (обновляем каждые 9 минут, чтобы не допустить истечения)
        const val WAKE_LOCK_RENEWAL_INTERVAL_MS = 9 * 60 * 1000L
    }

    private val binder = WorkoutTrackingBinder()
    private var fusedLocationClient: FusedLocationProviderClient? = null
    private var locationCallback: LocationCallback? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var currentLocationRequest: LocationRequest? = null

    // Данные тренировки
    private var currentSession = WorkoutSession()
    private var isCurrentlyTracking = false
    private var lastLocation: Location? = null
    private var lastUpdateTime: Long = 0
    private var selectedWorkoutType: WorkoutType = WorkoutType.EASY_RUN

    // Callback для уведомления UI об изменениях
    private var sessionUpdateCallback: ((WorkoutSession) -> Unit)? = null
    
    // Таймер для периодического запроса местоположения
    private var locationTimer: Timer? = null
    private var lastLocationTime: Long = 0
    /** Последний применённый интервал Fused Location (мс) — не пересоздаём request без смены «корзины». */
    private var lastAppliedAdaptiveIntervalMs: Long = -1L

    // Обновление времени тренировки только на главном потоке (избегаем гонок с LocationCallback)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var workoutTimeRunnable: Runnable? = null
    
    // Таймер для продления wake lock во время длительных тренировок
    private var wakeLockRenewalTimer: Timer? = null

    inner class WorkoutTrackingBinder : Binder() {
        fun getService(): WorkoutTrackingService = this@WorkoutTrackingService
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        setupLocationCallback()
        // Не приобретаем wake lock сразу - только при старте тренировки
        initializeWakeLock()
    }
    
    private fun initializeWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "RunnerApp::WorkoutTrackingWakeLock"
        )
        wakeLock?.setReferenceCounted(false) // Не использовать reference counting
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
                startWorkout()
            }
            ACTION_PAUSE_WORKOUT -> pauseWorkout()
            ACTION_RESUME_WORKOUT -> resumeWorkout()
            ACTION_STOP_WORKOUT -> stopWorkout()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()
        stopWorkoutTimer()
        stopLocationUpdates()
        stopPeriodicLocationRequest()
        stopWakeLockRenewal()
        releaseWakeLock()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Трекинг тренировок",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Уведомления о активной тренировке"
                setShowBadge(false)
            }
            
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_OPEN_TRACKING, true)
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val timeText = formatTime(currentSession.currentTime)
        val distanceText = String.format("%.2f км", currentSession.distance)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("$timeText • $distanceText")
            .setSmallIcon(R.drawable.ic_menu_run)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT) // Повышаем приоритет
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(false) // Не закрываем при нажатии
            .build()
    }

    private fun formatTime(milliseconds: Long): String {
        val totalSeconds = milliseconds / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return String.format("%02d:%02d:%02d", hours, minutes, seconds)
    }

    private fun updateNotification() {
        val notification = createNotification()
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun acquireWakeLock() {
        wakeLock?.let {
            if (!it.isHeld) {
                it.acquire()
                android.util.Log.d("WorkoutTrackingService", "Wake lock acquired")
            } else {
                android.util.Log.d("WorkoutTrackingService", "Wake lock already held, skipping extra acquire")
            }
        }
    }

    private fun renewWakeLockIfHeld() {
        wakeLock?.let {
            if (!it.isHeld) return@let
            try {
                it.acquire(WAKE_LOCK_RENEWAL_INTERVAL_MS)
                android.util.Log.d("WorkoutTrackingService", "Wake lock renewed (timed)")
            } catch (e: Exception) {
                android.util.Log.w("WorkoutTrackingService", "Could not renew wake lock: ${e.message}")
            }
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                android.util.Log.d("WorkoutTrackingService", "Wake lock released")
            }
        }
        // Останавливаем таймер продления wake lock
        stopWakeLockRenewal()
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

    private fun updateLocation(location: Location) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { updateLocation(location) }
            return
        }
        android.util.Log.d("WorkoutTrackingService", "updateLocation called: lat=${location.latitude}, lon=${location.longitude}, accuracy=${location.accuracy}m, speed=${location.speed}m/s")
        
        val newTrackPoints = currentSession.trackPoints.toMutableList()
        val newTrackDataPoints = currentSession.trackDataPoints.toMutableList()
        val newRawTrackDataPoints = currentSession.rawTrackDataPoints.toMutableList()
        
        if (isCurrentlyTracking && !currentSession.isPaused) {
            android.util.Log.d("WorkoutTrackingService", "Currently tracking, processing location")
            
            val previousLocation = lastLocation
            val rawTrackPoint = TrackPoint(
                latitude = location.latitude,
                longitude = location.longitude,
                timestamp = if (location.time > 0) location.time else System.currentTimeMillis(),
                accuracy = location.accuracy,
                speed = location.speed,
                altitude = location.altitude
            )
            newRawTrackDataPoints.add(rawTrackPoint)

            val filteredLocation = GpsFilter.filterGpsOutlier(
                location,
                previousLocation,
                selectedWorkoutType.maxReasonableGpsSpeedMps()
            )
            if (filteredLocation == null) {
                android.util.Log.w("WorkoutTrackingService", "GPS point filtered as outlier/invalid: lat=${location.latitude}, lon=${location.longitude}, acc=${location.accuracy}m")
                decimateRawPointsIfNeeded(newRawTrackDataPoints)
                currentSession = currentSession.copy(
                    currentLocation = location,
                    rawTrackDataPoints = newRawTrackDataPoints
                )
                sessionUpdateCallback?.invoke(currentSession)
                updateNotification()
                return
            }

            // Минимальная дистанция между точками (2 метра)
            if (previousLocation != null) {
                val distanceToLastMeters = filteredLocation.distanceTo(previousLocation)
                if (distanceToLastMeters < MIN_POINT_DISTANCE_METERS) {
                    android.util.Log.d("WorkoutTrackingService", "Point too close to previous: ${distanceToLastMeters}m — skipped")
                    decimateRawPointsIfNeeded(newRawTrackDataPoints)
                    currentSession = currentSession.copy(
                        currentLocation = filteredLocation,
                        rawTrackDataPoints = newRawTrackDataPoints
                    )
                    sessionUpdateCallback?.invoke(currentSession)
                    updateNotification()
                    return
                }
            }

            val validGeoPoint = GpsFilter.createValidGeoPoint(filteredLocation)
            if (validGeoPoint != null) {
                newTrackPoints.add(validGeoPoint)
            }

            val trackPoint = TrackPoint(
                latitude = filteredLocation.latitude,
                longitude = filteredLocation.longitude,
                timestamp = filteredLocation.time,
                accuracy = filteredLocation.accuracy,
                speed = filteredLocation.speed,
                altitude = filteredLocation.altitude
            )
            newTrackDataPoints.add(trackPoint)

            if (newTrackPoints.size == newTrackDataPoints.size) {
                decimateSyncedTrackPoints(newTrackPoints, newTrackDataPoints)
            }
            decimateRawPointsIfNeeded(newRawTrackDataPoints)

            var newDistance = currentSession.distance
            if (previousLocation != null) {
                val segmentDistanceMeters = filteredLocation.distanceTo(previousLocation)
                newDistance += segmentDistanceMeters / 1000f
                android.util.Log.d("WorkoutTrackingService", "Distance segment: ${segmentDistanceMeters}m, total: ${newDistance}km")
            } else {
                android.util.Log.d("WorkoutTrackingService", "First GPS point, no distance calculated")
            }

            val currentTime = System.currentTimeMillis()
            val timeDiffMs = if (lastUpdateTime > 0) currentTime - lastUpdateTime else 0
            val currentSpeed = computeCurrentSpeed(previousLocation, filteredLocation, timeDiffMs)
            val avgSpeed = computeAverageSpeedKmH(newDistance, currentSession.currentTime)
            val currentPace = computePaceMinPerKm(currentSpeed)
            val avgPace = computePaceMinPerKm(avgSpeed)
            val userPrefs = UserPreferences(this)
            val calories = FormatUtils.calculateCalories(newDistance, userPrefs.userWeight)

            currentSession = currentSession.copy(
                currentLocation = filteredLocation,
                trackPoints = newTrackPoints,
                trackDataPoints = newTrackDataPoints,
                rawTrackDataPoints = newRawTrackDataPoints,
                distance = newDistance,
                currentSpeed = currentSpeed,
                avgSpeed = avgSpeed,
                currentPace = currentPace,
                avgPace = avgPace,
                calories = calories,
                gpsStatus = com.example.runner.ui.tracking.GpsStatus.FOUND
            )

            // Оптимизируем интервал GPS запросов в зависимости от скорости (каждые 10 обновлений)
            if (newTrackPoints.size % 10 == 0) {
                updateLocationRequestInterval(currentSpeed)
            }

            // Обновляем маркеры времени и последнюю локацию после вычислений
            lastLocation = filteredLocation
            lastUpdateTime = currentTime
            lastLocationTime = currentTime
            android.util.Log.d("WorkoutTrackingService", "Timing updated: lastUpdateTime=$lastUpdateTime, currentTime=$currentTime")
        } else {
            android.util.Log.d("WorkoutTrackingService", "Not tracking or paused: isCurrentlyTracking=$isCurrentlyTracking, isPaused=${currentSession.isPaused}")
            currentSession = currentSession.copy(
                currentLocation = location,
                rawTrackDataPoints = if (isCurrentlyTracking) {
                    newRawTrackDataPoints.also {
                        val rawTrackPoint = TrackPoint(
                            latitude = location.latitude,
                            longitude = location.longitude,
                            timestamp = if (location.time > 0) location.time else System.currentTimeMillis(),
                            accuracy = location.accuracy,
                            speed = location.speed,
                            altitude = location.altitude
                        )
                        it.add(rawTrackPoint)
                        decimateRawPointsIfNeeded(it)
                    }
                } else currentSession.rawTrackDataPoints,
                gpsStatus = com.example.runner.ui.tracking.GpsStatus.FOUND
            )
        }

        sessionUpdateCallback?.invoke(currentSession)
        updateNotification()
    }

    private fun decimateSyncedTrackPoints(
        trackPoints: MutableList<GeoPoint>,
        trackDataPoints: MutableList<TrackPoint>
    ) {
        while (trackPoints.size > MAX_TRACK_POINTS_DISPLAY) {
            val before = trackPoints.size
            val newTp = trackPoints.filterIndexed { i, _ -> i % 2 == 0 || i == trackPoints.lastIndex }.toMutableList()
            val newTd = trackDataPoints.filterIndexed { i, _ -> i % 2 == 0 || i == trackDataPoints.lastIndex }.toMutableList()
            trackPoints.clear()
            trackPoints.addAll(newTp)
            trackDataPoints.clear()
            trackDataPoints.addAll(newTd)
            if (trackPoints.size >= before) break
        }
    }

    private fun decimateRawPointsIfNeeded(raw: MutableList<TrackPoint>) {
        while (raw.size > MAX_RAW_TRACK_POINTS) {
            val before = raw.size
            val newR = raw.filterIndexed { i, _ -> i % 2 == 0 || i == raw.lastIndex }.toMutableList()
            raw.clear()
            raw.addAll(newR)
            if (raw.size >= before) break
        }
    }

    private fun computeCurrentSpeed(previous: Location?, current: Location, timeDiffMs: Long): Float {
        if (previous == null || timeDiffMs <= 0) {
            android.util.Log.d("WorkoutTrackingService", "Speed calculation skipped: timeDiffMs=$timeDiffMs, hasPrev=${previous != null}")
            return 0f
        }
        val distanceKm = current.distanceTo(previous) / 1000f
        val hours = timeDiffMs / (1000f * 3600f)
        if (hours <= 0f) return 0f
        val speed = distanceKm / hours
        android.util.Log.d("WorkoutTrackingService", "Speed calculation: distance=${distanceKm}km, time=${hours}h, speed=${speed}km/h")
        return speed
    }

    private fun computeAverageSpeedKmH(distanceKm: Float, activeTimeMs: Long): Float {
        if (distanceKm <= 0f || activeTimeMs <= 0L) return 0f
        val hours = activeTimeMs / (1000f * 3600f)
        if (hours <= 0f) return 0f
        return distanceKm / hours
    }

    private fun computePaceMinPerKm(speedKmH: Float): Float {
        if (speedKmH <= 0f) return 0f
        return 60f / speedKmH
    }

    fun startWorkout() {
        android.util.Log.d("WorkoutTrackingService", "startWorkout called")

        if (currentSession.isTracking && currentSession.isPaused) {
            android.util.Log.w("WorkoutTrackingService", "startWorkout ignored: workout is paused, use resume")
            return
        }

        if (isCurrentlyTracking && currentSession.isTracking && !currentSession.isPaused) {
            android.util.Log.w("WorkoutTrackingService", "startWorkout ignored: workout already running")
            return
        }
        
        // Проверяем разрешения GPS
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            android.util.Log.e("WorkoutTrackingService", "GPS permission not granted, cannot start workout")
            currentSession = currentSession.copy(gpsStatus = com.example.runner.ui.tracking.GpsStatus.DENIED)
            sessionUpdateCallback?.invoke(currentSession)
            return
        }

        lastLocation = null
        lastUpdateTime = 0L
        lastLocationTime = 0L
        lastAppliedAdaptiveIntervalMs = -1L
        
        acquireWakeLock()
        startWakeLockRenewal()
        
        val currentTime = System.currentTimeMillis()
        isCurrentlyTracking = true
        android.util.Log.d("WorkoutTrackingService", "Setting isCurrentlyTracking = true")
        currentSession = WorkoutSession(
            isTracking = true,
            isPaused = false,
            startTime = currentTime,
            pauseTime = 0L,
            totalPauseDuration = 0L,
            currentTime = 0L,
            distance = 0f,
            avgPace = 0f,
            currentPace = 0f,
            avgSpeed = 0f,
            currentSpeed = 0f,
            heartRate = 0,
            calories = 0,
            gpsStatus = com.example.runner.ui.tracking.GpsStatus.SEARCHING,
            trackPoints = emptyList(),
            trackDataPoints = emptyList(),
            rawTrackDataPoints = emptyList(),
            currentLocation = null
        )
        
        startLocationUpdates()
        startPeriodicLocationRequest()
        startWorkoutTimer()
        startForeground(NOTIFICATION_ID, createNotification())
        sessionUpdateCallback?.invoke(currentSession)
        
        android.util.Log.d("WorkoutTrackingService", "Workout started successfully")
    }

    fun pauseWorkout() {
        android.util.Log.d("WorkoutTrackingService", "pauseWorkout called")
        isCurrentlyTracking = false
        val currentTime = System.currentTimeMillis()
        currentSession = currentSession.copy(
            isPaused = true,
            pauseTime = currentTime
            // isTracking остается true, так как тренировка не остановлена, а только на паузе
        )
        android.util.Log.d("WorkoutTrackingService", "Session updated: isTracking=${currentSession.isTracking}, isPaused=${currentSession.isPaused}")
        // Останавливаем продление wake lock и освобождаем его во время паузы для экономии батареи
        stopWakeLockRenewal()
        releaseWakeLock()
        stopPeriodicLocationRequest()
        stopWorkoutTimer()
        sessionUpdateCallback?.invoke(currentSession)
        updateNotification()
    }

    fun resumeWorkout() {
        android.util.Log.d("WorkoutTrackingService", "resumeWorkout called")
        val currentTime = System.currentTimeMillis()
        val pauseDuration = currentTime - currentSession.pauseTime
        isCurrentlyTracking = true
        currentSession = currentSession.copy(
            isPaused = false,
            pauseTime = 0,
            totalPauseDuration = currentSession.totalPauseDuration + pauseDuration
        )
        android.util.Log.d("WorkoutTrackingService", "Session updated: isTracking=${currentSession.isTracking}, isPaused=${currentSession.isPaused}")
        // Приобретаем wake lock при возобновлении
        acquireWakeLock()
        startWakeLockRenewal()
        startPeriodicLocationRequest()
        startWorkoutTimer()
        sessionUpdateCallback?.invoke(currentSession)
        updateNotification()
    }

    fun stopWorkout() {
        isCurrentlyTracking = false
        lastAppliedAdaptiveIntervalMs = -1L
        currentSession = currentSession.copy(
            isTracking = false,
            isPaused = false
        )
        stopLocationUpdates()
        stopPeriodicLocationRequest()
        stopWorkoutTimer()
        stopWakeLockRenewal()
        releaseWakeLock()
        sessionUpdateCallback?.invoke(currentSession)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startLocationUpdates() {
        // Используем оптимизированные настройки GPS
        val locationRequest = com.example.runner.util.GpsConfig.createWorkoutLocationRequest()
        currentLocationRequest = locationRequest

        try {
            // Запрашиваем обновления с высоким приоритетом
            fusedLocationClient?.requestLocationUpdates(
                locationRequest,
                locationCallback!!,
                mainLooper
            )
            lastAppliedAdaptiveIntervalMs = GpsConfig.HIGH_ACCURACY_INTERVAL
            
            // Дополнительно запрашиваем последнее известное местоположение
            fusedLocationClient?.lastLocation?.addOnSuccessListener { location ->
                location?.let {
                    updateLocation(it)
                }
            }
            
        } catch (e: SecurityException) {
            // Обработка ошибки разрешений
            android.util.Log.e("WorkoutTrackingService", "Location permission denied", e)
            currentSession = currentSession.copy(
                gpsStatus = com.example.runner.ui.tracking.GpsStatus.DENIED
            )
            sessionUpdateCallback?.invoke(currentSession)
        } catch (e: Exception) {
            // Обработка других ошибок GPS
            android.util.Log.e("WorkoutTrackingService", "GPS error: ${e.message}", e)
            currentSession = currentSession.copy(
                gpsStatus = com.example.runner.ui.tracking.GpsStatus.LOST
            )
            sessionUpdateCallback?.invoke(currentSession)
        }
    }

    private fun stopLocationUpdates() {
        locationCallback?.let { callback ->
            fusedLocationClient?.removeLocationUpdates(callback)
        }
    }

    private fun startPeriodicLocationRequest() {
        // Останавливаем предыдущий таймер если он есть
        locationTimer?.cancel()
        
        locationTimer = Timer()
        locationTimer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                // Проверяем, не прошло ли слишком много времени с последнего обновления
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastLocationTime > NO_LOCATION_UPDATE_TIMEOUT_MS) {
                    // Принудительно запрашиваем местоположение
                    fusedLocationClient?.lastLocation?.addOnSuccessListener { location ->
                        location?.let {
                            updateLocation(it)
                            lastLocationTime = currentTime
                        }
                    }
                }
            }
        }, PERIODIC_LOCATION_REQUEST_INTERVAL_MS, PERIODIC_LOCATION_REQUEST_INTERVAL_MS)
    }
    
    /**
     * Обновляет интервал GPS запросов в зависимости от текущей скорости
     * для оптимизации потребления батареи
     */
    private fun updateLocationRequestInterval(currentSpeed: Float) {
        if (!isCurrentlyTracking || currentSession.isPaused) {
            return
        }
        
        val adaptiveInterval = GpsConfig.getAdaptiveInterval(currentSpeed)
        if (adaptiveInterval == lastAppliedAdaptiveIntervalMs) {
            return
        }

        android.util.Log.d("WorkoutTrackingService", "Updating location request interval to $adaptiveInterval ms (speed: $currentSpeed km/h)")
        
        try {
            // Останавливаем текущие обновления
            locationCallback?.let { callback ->
                fusedLocationClient?.removeLocationUpdates(callback)
            }
            
            // Создаем новый LocationRequest с адаптивным интервалом
            val newLocationRequest = GpsConfig.createAdaptiveLocationRequest(adaptiveInterval)
            currentLocationRequest = newLocationRequest
            
            // Перезапускаем обновления с новым интервалом
            fusedLocationClient?.requestLocationUpdates(
                newLocationRequest,
                locationCallback!!,
                mainLooper
            )
            
            android.util.Log.d("WorkoutTrackingService", "Location request updated successfully")
            lastAppliedAdaptiveIntervalMs = adaptiveInterval
            
        } catch (e: SecurityException) {
            android.util.Log.e("WorkoutTrackingService", "SecurityException updating location request: ${e.message}", e)
        } catch (e: Exception) {
            android.util.Log.e("WorkoutTrackingService", "Error updating location request: ${e.message}", e)
        }
        
        // Обновляем периодический таймер
        updatePeriodicTimerInterval(currentSpeed)
    }
    
    /**
     * Обновляет интервал периодического таймера в зависимости от скорости
     */
    private fun updatePeriodicTimerInterval(currentSpeed: Float) {
        // Если скорость низкая, увеличиваем интервал периодических запросов
        val periodicInterval = if (currentSpeed < 1f) {
            PERIODIC_LOCATION_REQUEST_INTERVAL_MS * 2 // Удваиваем интервал при остановке
        } else {
            PERIODIC_LOCATION_REQUEST_INTERVAL_MS
        }
        
        // Перезапускаем таймер с новым интервалом только если он активен
        if (locationTimer != null && isCurrentlyTracking && !currentSession.isPaused) {
            stopPeriodicLocationRequest()
            locationTimer = Timer()
            locationTimer?.scheduleAtFixedRate(object : TimerTask() {
                override fun run() {
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastLocationTime > NO_LOCATION_UPDATE_TIMEOUT_MS) {
                        fusedLocationClient?.lastLocation?.addOnSuccessListener { location ->
                            location?.let {
                                updateLocation(it)
                                lastLocationTime = currentTime
                            }
                        }
                    }
                }
            }, periodicInterval, periodicInterval)
        }
    }

    private fun stopPeriodicLocationRequest() {
        locationTimer?.cancel()
        locationTimer = null
    }
    
    /**
     * Запускает периодическое продление wake lock для длительных тренировок
     * Это предотвращает истечение wake lock во время активного трекинга
     */
    private fun startWakeLockRenewal() {
        // Останавливаем предыдущий таймер если он есть
        wakeLockRenewalTimer?.cancel()
        
        wakeLockRenewalTimer = Timer()
        wakeLockRenewalTimer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                // Продлеваем wake lock только во время активного трекинга
                if (isCurrentlyTracking && !currentSession.isPaused) {
                    renewWakeLockIfHeld()
                    android.util.Log.d("WorkoutTrackingService", "Wake lock automatically renewed")
                } else {
                    // Если тренировка на паузе или остановлена, останавливаем обновления
                    stopWakeLockRenewal()
                }
            }
        }, WAKE_LOCK_RENEWAL_INTERVAL_MS, WAKE_LOCK_RENEWAL_INTERVAL_MS)
    }
    
    /**
     * Останавливает периодическое продление wake lock
     */
    private fun stopWakeLockRenewal() {
        wakeLockRenewalTimer?.cancel()
        wakeLockRenewalTimer = null
    }
    
    private fun startWorkoutTimer() {
        android.util.Log.d("WorkoutTrackingService", "Starting workout timer")
        stopWorkoutTimer()
        val r = object : Runnable {
            override fun run() {
                if (!isCurrentlyTracking || currentSession.isPaused) {
                    workoutTimeRunnable = null
                    return
                }
                val currentTime = System.currentTimeMillis()
                val elapsedTime = currentTime - currentSession.startTime - currentSession.totalPauseDuration
                currentSession = currentSession.copy(currentTime = elapsedTime)
                sessionUpdateCallback?.invoke(currentSession)
                android.util.Log.d("WorkoutTrackingService", "Updated workout time: $elapsedTime")
                updateNotification()
                mainHandler.postDelayed(this, WORKOUT_TIMER_INTERVAL_MS)
            }
        }
        workoutTimeRunnable = r
        mainHandler.post(r)
    }
    
    private fun stopWorkoutTimer() {
        android.util.Log.d("WorkoutTrackingService", "Stopping workout timer")
        workoutTimeRunnable?.let { mainHandler.removeCallbacks(it) }
        workoutTimeRunnable = null
    }

    // Методы для взаимодействия с UI
    fun setSessionUpdateCallback(callback: (WorkoutSession) -> Unit) {
        sessionUpdateCallback = callback
    }

    fun getCurrentSession(): WorkoutSession = currentSession

    fun isTracking(): Boolean = currentSession.isTracking
}
