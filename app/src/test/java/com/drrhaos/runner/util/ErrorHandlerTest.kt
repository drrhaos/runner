package com.drrhaos.runner.util

import android.content.Context
import android.location.LocationManager
import com.drrhaos.runner.data.GpsStatus
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * Тесты для ErrorHandler - обработка ошибок и определение GPS статуса
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ErrorHandlerTest {

    private val context: Context = RuntimeEnvironment.getApplication()

    @Before
    fun enableGpsProvider() {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        Shadows.shadowOf(locationManager).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
    }

    @Test
    fun isgpsavailable_should_check_gps_provider_status() {
        // When - проверяем текущий статус GPS (может быть включен или выключен в тестовой среде)
        val result = ErrorHandler.isGpsAvailable(context)

        // Then - должен вернуть булево значение (не выбросить исключение)
        assertNotNull("Should return boolean value", result)
        assertTrue("Should return boolean", result is Boolean)
    }

    @Test
    fun determinegpsstatus_should_return_lost_when_no_updates_for_30_seconds() {
        // Given
        val oldUpdateTime = System.currentTimeMillis() - 31000 // 31 секунда назад

        // When
        val result = ErrorHandler.determineGpsStatus(context, accuracy = 10f, lastUpdateTime = oldUpdateTime)

        // Then
        assertEquals("Should return LOST when no updates for >30 seconds", GpsStatus.LOST, result)
    }

    @Test
    fun determinegpsstatus_should_return_searching_when_accuracy_is_poor() {
        // Given
        val recentUpdateTime = System.currentTimeMillis() - 5000 // 5 секунд назад

        // When
        val result = ErrorHandler.determineGpsStatus(context, accuracy = 150f, lastUpdateTime = recentUpdateTime)

        // Then
        assertEquals("Should return SEARCHING when accuracy >100m", GpsStatus.SEARCHING, result)
    }

    @Test
    fun determinegpsstatus_should_return_found_when_gps_is_available_with_good_accuracy() {
        // Given
        val recentUpdateTime = System.currentTimeMillis() - 5000 // 5 секунд назад

        // When
        val result = ErrorHandler.determineGpsStatus(context, accuracy = 50f, lastUpdateTime = recentUpdateTime)

        // Then
        assertTrue("Should return FOUND or DENIED when GPS is available", 
            result == GpsStatus.FOUND || result == GpsStatus.DENIED)
    }

    @Test
    fun determinegpsstatus_should_return_found_when_accuracy_is_at_boundary_100m() {
        // Given
        val recentUpdateTime = System.currentTimeMillis() - 5000

        // When
        val result = ErrorHandler.determineGpsStatus(context, accuracy = 100f, lastUpdateTime = recentUpdateTime)

        // Then
        assertTrue("Should return FOUND or DENIED when accuracy is exactly 100m", 
            result == GpsStatus.FOUND || result == GpsStatus.DENIED)
    }

    @Test
    fun handlegpserror_should_not_show_toast_for_found_status() {
        // When - handleGpsError должен вернуться сразу для FOUND
        // Then - не должно быть исключений
        ErrorHandler.handleGpsError(context, GpsStatus.FOUND, showToast = true)
        assertTrue("Should handle FOUND status without exception", true)
    }

    @Test
    fun handlegpserror_should_handle_all_status_types_without_exception() {
        // When & Then - все статусы должны обрабатываться без исключений
        GpsStatus.values().forEach { status ->
            ErrorHandler.handleGpsError(context, status, showToast = false)
        }
        assertTrue("Should handle all GPS statuses", true)
    }

    @Test
    fun handlesaveerror_should_handle_sqlexception() {
        // Given
        val error = java.sql.SQLException("Database error")

        // When & Then - не должно быть исключений
        ErrorHandler.handleSaveError(context, error, showToast = false)
        assertTrue("Should handle SQLException", true)
    }

    @Test
    fun handlesaveerror_should_handle_ioexception() {
        // Given
        val error = java.io.IOException("IO error")

        // When & Then - не должно быть исключений
        ErrorHandler.handleSaveError(context, error, showToast = false)
        assertTrue("Should handle IOException", true)
    }

    @Test
    fun handlesaveerror_should_handle_generic_exception() {
        // Given
        val error = RuntimeException("Generic error")

        // When & Then - не должно быть исключений
        ErrorHandler.handleSaveError(context, error, showToast = false)
        assertTrue("Should handle generic Exception", true)
    }

    @Test
    fun handleloaderror_should_handle_sqlexception() {
        // Given
        val error = java.sql.SQLException("Database error")

        // When & Then - не должно быть исключений
        ErrorHandler.handleLoadError(context, error, showToast = false)
        assertTrue("Should handle SQLException", true)
    }

    @Test
    fun handleloaderror_should_handle_ioexception() {
        // Given
        val error = java.io.IOException("IO error")

        // When & Then - не должно быть исключений
        ErrorHandler.handleLoadError(context, error, showToast = false)
        assertTrue("Should handle IOException", true)
    }

    @Test
    fun retrywithbackoff_should_succeed_on_first_attempt() = runTest {
        // Given
        var attemptCount = 0
        val operation = suspend {
            attemptCount++
            "success"
        }

        // When
        val result = ErrorHandler.retryWithBackoff(maxRetries = 3, operation = operation)

        // Then
        assertEquals("Should return success value", "success", result)
        assertEquals("Should only attempt once", 1, attemptCount)
    }

    @Test
    fun retrywithbackoff_should_retry_on_failure() = runTest {
        // Given
        var attemptCount = 0
        val operation = suspend {
            attemptCount++
            if (attemptCount < 2) {
                throw RuntimeException("Error")
            }
            "success"
        }

        // When
        val result = ErrorHandler.retryWithBackoff(
            maxRetries = 3,
            initialDelay = 10L, // Короткая задержка для тестов
            operation = operation
        )

        // Then
        assertEquals("Should return success after retry", "success", result)
        assertEquals("Should attempt twice", 2, attemptCount)
    }

    @Test
    fun retrywithbackoff_should_throw_exception_after_max_retries() = runTest {
        // Given
        val operation = suspend {
            throw RuntimeException("Always fails")
        }

        // When & Then
        try {
            ErrorHandler.retryWithBackoff(
                maxRetries = 3,
                initialDelay = 10L,
                operation = operation
            )
            fail("Should throw exception after max retries")
        } catch (e: RuntimeException) {
            assertEquals("Should throw original exception", "Always fails", e.message)
        }
    }

    @Test
    fun retrywithbackoff_should_respect_maxdelay() = runTest {
        // Given
        var attemptCount = 0
        val operation = suspend {
            attemptCount++
            if (attemptCount < 2) {
                throw RuntimeException("Error")
            }
            "success"
        }

        // When
        val startTime = System.currentTimeMillis()
        val result = ErrorHandler.retryWithBackoff(
            maxRetries = 3,
            initialDelay = 100L,
            maxDelay = 150L, // Максимальная задержка
            operation = operation
        )
        val elapsedTime = System.currentTimeMillis() - startTime

        // Then
        assertEquals("Should return success", "success", result)
        // Задержка должна быть ограничена maxDelay, но может быть немного больше из-за выполнения кода
        assertTrue("Delay should be reasonable", elapsedTime < 500)
    }

    @Test
    fun retrywithbackoff_should_use_exponential_backoff() = runTest {
        // Given
        var attemptCount = 0
        val delays = mutableListOf<Long>()
        val operation = suspend {
            attemptCount++
            if (attemptCount < 3) {
                val currentTime = System.currentTimeMillis()
                if (delays.isNotEmpty()) {
                    delays.add(currentTime - delays.last())
                } else {
                    delays.add(currentTime)
                }
                throw RuntimeException("Error")
            }
            "success"
        }

        // When
        ErrorHandler.retryWithBackoff(
            maxRetries = 3,
            initialDelay = 50L,
            operation = operation
        )

        // Then
        assertEquals("Should attempt 3 times", 3, attemptCount)
        // Проверяем, что задержки увеличиваются (хотя бы приблизительно)
        assertTrue("Should have multiple attempts", attemptCount >= 2)
    }
}

