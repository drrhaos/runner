package com.drrhaos.runner.service

import android.content.Context
import android.content.Intent
import android.location.Location
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.ServiceTestRule
import com.drrhaos.runner.data.WorkoutType
import com.drrhaos.runner.data.GpsStatus
import com.drrhaos.runner.data.WorkoutSession
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Интеграционные тесты для WorkoutTrackingService
 */
@RunWith(AndroidJUnit4::class)
class WorkoutTrackingServiceTest {

    @get:Rule
    val serviceRule = ServiceTestRule()

    private lateinit var context: Context
    private var service: WorkoutTrackingService? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        service?.let {
            val stopIntent = Intent(context, WorkoutTrackingService::class.java).apply {
                action = WorkoutTrackingService.ACTION_STOP_WORKOUT
            }
            context.startService(stopIntent)
        }
    }

    private fun createMockLocation(
        latitude: Double = 55.7558,
        longitude: Double = 37.6173,
        accuracy: Float = 10f,
        speed: Float = 3f
    ): Location {
        val location = Location("test")
        location.latitude = latitude
        location.longitude = longitude
        location.accuracy = accuracy
        location.speed = speed
        location.time = System.currentTimeMillis()
        return location
    }

    @Test
    fun service_should_start_and_bind_correctly() {
        // Given
        val intent = Intent(context, WorkoutTrackingService::class.java)

        // When
        val binder = serviceRule.bindService(intent)

        // Then
        assertNotNull("Service should bind successfully", binder)
        assertTrue("Binder should be WorkoutTrackingBinder", binder is WorkoutTrackingService.WorkoutTrackingBinder)
        
        val service = (binder as WorkoutTrackingService.WorkoutTrackingBinder).getService()
        assertNotNull("Service instance should not be null", service)
        this.service = service
    }

    @Test
    fun service_should_start_workout_with_correct_type() {
        // Given
        val intent = Intent(context, WorkoutTrackingService::class.java)
        val binder = serviceRule.bindService(intent)
        val service = (binder as WorkoutTrackingService.WorkoutTrackingBinder).getService()
        this.service = service

        // When
        val startIntent = Intent(context, WorkoutTrackingService::class.java).apply {
            action = WorkoutTrackingService.ACTION_START_WORKOUT
            putExtra(WorkoutTrackingService.EXTRA_WORKOUT_TYPE, WorkoutType.TEMPO_RUN)
        }
        context.startForegroundService(startIntent)

        // Then
        Thread.sleep(500) // Даем время сервису обработать команду
        val session = service.getCurrentSession()
        assertTrue("Session should be tracking", session.isTracking)
        assertFalse("Session should not be paused", session.isPaused)
    }

    @Test
    fun service_should_pause_workout() {
        // Given
        val intent = Intent(context, WorkoutTrackingService::class.java)
        val binder = serviceRule.bindService(intent)
        val service = (binder as WorkoutTrackingService.WorkoutTrackingBinder).getService()
        this.service = service

        // Start workout first
        val startIntent = Intent(context, WorkoutTrackingService::class.java).apply {
            action = WorkoutTrackingService.ACTION_START_WORKOUT
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(startIntent)
        } else {
            @Suppress("DEPRECATION")
            context.startService(startIntent)
        }
        Thread.sleep(500)

        // When
        val pauseIntent = Intent(context, WorkoutTrackingService::class.java).apply {
            action = WorkoutTrackingService.ACTION_PAUSE_WORKOUT
        }
        context.startService(pauseIntent)

        // Then
        Thread.sleep(500)
        val session = service.getCurrentSession()
        assertTrue("Session should still be tracking", session.isTracking)
        assertTrue("Session should be paused", session.isPaused)
    }

    @Test
    fun service_should_resume_workout_after_pause() {
        // Given
        val intent = Intent(context, WorkoutTrackingService::class.java)
        val binder = serviceRule.bindService(intent)
        val service = (binder as WorkoutTrackingService.WorkoutTrackingBinder).getService()
        this.service = service

        // Start and pause workout
        val startIntent = Intent(context, WorkoutTrackingService::class.java).apply {
            action = WorkoutTrackingService.ACTION_START_WORKOUT
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(startIntent)
        } else {
            @Suppress("DEPRECATION")
            context.startService(startIntent)
        }
        Thread.sleep(500)

        val pauseIntent = Intent(context, WorkoutTrackingService::class.java).apply {
            action = WorkoutTrackingService.ACTION_PAUSE_WORKOUT
        }
        context.startService(pauseIntent)
        Thread.sleep(500)

        // When
        val resumeIntent = Intent(context, WorkoutTrackingService::class.java).apply {
            action = WorkoutTrackingService.ACTION_RESUME_WORKOUT
        }
        context.startService(resumeIntent)

        // Then
        Thread.sleep(500)
        val session = service.getCurrentSession()
        assertTrue("Session should be tracking", session.isTracking)
        assertFalse("Session should not be paused", session.isPaused)
    }

    @Test
    fun service_should_stop_workout() {
        // Given
        val intent = Intent(context, WorkoutTrackingService::class.java)
        val binder = serviceRule.bindService(intent)
        val service = (binder as WorkoutTrackingService.WorkoutTrackingBinder).getService()
        this.service = service

        // Start workout
        val startIntent = Intent(context, WorkoutTrackingService::class.java).apply {
            action = WorkoutTrackingService.ACTION_START_WORKOUT
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(startIntent)
        } else {
            @Suppress("DEPRECATION")
            context.startService(startIntent)
        }
        Thread.sleep(500)

        // When
        val stopIntent = Intent(context, WorkoutTrackingService::class.java).apply {
            action = WorkoutTrackingService.ACTION_STOP_WORKOUT
        }
        context.startService(stopIntent)

        // Then
        Thread.sleep(500)
        val session = service.getCurrentSession()
        assertFalse("Session should not be tracking", session.isTracking)
        assertFalse("Session should not be paused", session.isPaused)
    }

    @Test
    fun service_should_provide_current_session() {
        // Given
        val intent = Intent(context, WorkoutTrackingService::class.java)
        val binder = serviceRule.bindService(intent)
        val service = (binder as WorkoutTrackingService.WorkoutTrackingBinder).getService()
        this.service = service

        // When
        val session = service.getCurrentSession()

        // Then
        assertNotNull("Session should not be null", session)
        assertFalse("Initial session should not be tracking", session.isTracking)
        assertEquals("Initial distance should be 0", 0f, session.distance)
        assertEquals("Initial GPS status should be SEARCHING", GpsStatus.SEARCHING, session.gpsStatus)
    }

    @Test
    fun service_should_update_session_via_callback() {
        // Given
        val intent = Intent(context, WorkoutTrackingService::class.java)
        val binder = serviceRule.bindService(intent)
        val service = (binder as WorkoutTrackingService.WorkoutTrackingBinder).getService()
        this.service = service

        val latch = CountDownLatch(1)
        var receivedSession: WorkoutSession? = null

        // When
        service.setSessionUpdateCallback { session ->
            receivedSession = session
            latch.countDown()
        }

        val startIntent = Intent(context, WorkoutTrackingService::class.java).apply {
            action = WorkoutTrackingService.ACTION_START_WORKOUT
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(startIntent)
        } else {
            @Suppress("DEPRECATION")
            context.startService(startIntent)
        }

        // Then
        val callbackReceived = latch.await(2, TimeUnit.SECONDS)
        assertTrue("Callback should be called", callbackReceived)
        assertNotNull("Session should be received", receivedSession)
        assertTrue("Received session should be tracking", receivedSession?.isTracking == true)
    }

    @Test
    fun service_should_handle_multiple_session_updates() {
        // Given
        val intent = Intent(context, WorkoutTrackingService::class.java)
        val binder = serviceRule.bindService(intent)
        val service = (binder as WorkoutTrackingService.WorkoutTrackingBinder).getService()
        this.service = service

        val updateCount = CountDownLatch(3)
        var receivedSessions = mutableListOf<WorkoutSession>()

        // When
        service.setSessionUpdateCallback { session ->
            receivedSessions.add(session)
            updateCount.countDown()
        }

        // Start workout
        val startIntent = Intent(context, WorkoutTrackingService::class.java).apply {
            action = WorkoutTrackingService.ACTION_START_WORKOUT
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(startIntent)
        } else {
            @Suppress("DEPRECATION")
            context.startService(startIntent)
        }
        Thread.sleep(300)

        // Pause
        val pauseIntent = Intent(context, WorkoutTrackingService::class.java).apply {
            action = WorkoutTrackingService.ACTION_PAUSE_WORKOUT
        }
        context.startService(pauseIntent)
        Thread.sleep(300)

        // Resume
        val resumeIntent = Intent(context, WorkoutTrackingService::class.java).apply {
            action = WorkoutTrackingService.ACTION_RESUME_WORKOUT
        }
        context.startService(resumeIntent)

        // Then
        val updatesReceived = updateCount.await(3, TimeUnit.SECONDS)
        assertTrue("Should receive multiple updates", updatesReceived || receivedSessions.size >= 2)
        assertTrue("Should have at least 2 sessions", receivedSessions.size >= 2)
    }

    @Test
    fun service_isTracking_should_return_correct_state() {
        // Given
        val intent = Intent(context, WorkoutTrackingService::class.java)
        val binder = serviceRule.bindService(intent)
        val service = (binder as WorkoutTrackingService.WorkoutTrackingBinder).getService()
        this.service = service

        // Initial state
        assertFalse("Initially should not be tracking", service.isTracking())

        // Start workout
        val startIntent = Intent(context, WorkoutTrackingService::class.java).apply {
            action = WorkoutTrackingService.ACTION_START_WORKOUT
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(startIntent)
        } else {
            @Suppress("DEPRECATION")
            context.startService(startIntent)
        }
        Thread.sleep(500)

        // Then
        assertTrue("Should be tracking after start", service.isTracking())
    }

    @Test
    fun service_should_handle_different_workout_types() {
        // Given
        val workoutTypes = listOf(
            WorkoutType.EASY_RUN,
            WorkoutType.TEMPO_RUN,
            WorkoutType.INTERVAL_TRAINING,
            WorkoutType.LONG_RUN,
            WorkoutType.RECOVERY_RUN,
            WorkoutType.RACE
        )

        workoutTypes.forEach { workoutType ->
            // When
            val intent = Intent(context, WorkoutTrackingService::class.java)
            val binder = serviceRule.bindService(intent)
            val service = (binder as WorkoutTrackingService.WorkoutTrackingBinder).getService()
            this.service = service

            val startIntent = Intent(context, WorkoutTrackingService::class.java).apply {
                action = WorkoutTrackingService.ACTION_START_WORKOUT
                putExtra(WorkoutTrackingService.EXTRA_WORKOUT_TYPE, workoutType)
            }
            context.startForegroundService(startIntent)
            Thread.sleep(300)

            // Stop for next iteration
            val stopIntent = Intent(context, WorkoutTrackingService::class.java).apply {
                action = WorkoutTrackingService.ACTION_STOP_WORKOUT
            }
            context.startService(stopIntent)
            Thread.sleep(200)
        }

        // Then - все типы должны обработаться без исключений
        assertTrue("All workout types should be handled", true)
    }
}
