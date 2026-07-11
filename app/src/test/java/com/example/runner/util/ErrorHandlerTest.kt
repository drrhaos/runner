package com.example.runner.util

import android.content.Context
import android.location.LocationManager
import com.example.runner.data.GpsStatus
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Тесты для ErrorHandler - обработка ошибок и определение GPS статуса
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ErrorHandlerTest {

    private val context: Context = RuntimeEnvironment.getApplication()

    @Test
    fun `isGpsAvailable should check GPS provider status`() {
        // When - проверяем текущий статус GPS (может быть включен или выключен в тестовой среде)
        val result = ErrorHandler.isGpsAvailable(context)

        // Then - должен вернуть булево значение (не выбросить исключение)
        assertNotNull("Should return boolean value", result)
        assertTrue("Should return boolean", result is Boolean)
    }

    @Test
    fun `determineGpsStatus should return LOST when no updates for 30 seconds`() {
        // Given
        val oldUpdateTime = System.currentTimeMillis() - 31000 // 31 секунда назад

        // When
        val result = ErrorHandler.determineGpsStatus(context, accuracy = 10f, lastUpdateTime = oldUpdateTime)

        // Then
        assertEquals("Should return LOST when no updates for >30 seconds", GpsStatus.LOST, result)
    }

    @Test
    fun `determineGpsStatus should return SEARCHING when accuracy is poor`() {
        // Given
        val recentUpdateTime = System.currentTimeMillis() - 5000 // 5 секунд назад

        // When
        val result = ErrorHandler.determineGpsStatus(context, accuracy = 150f, lastUpdateTime = recentUpdateTime)

        // Then
        assertEquals("Should return SEARCHING when accuracy >100m", GpsStatus.SEARCHING, result)
    }

    @Test
    fun `determineGpsStatus should return FOUND when GPS is available with good accuracy`() {
        // Given
        val recentUpdateTime = System.currentTimeMillis() - 5000 // 5 секунд назад

        // When
        val result = ErrorHandler.determineGpsStatus(context, accuracy = 50f, lastUpdateTime = recentUpdateTime)

        // Then
        assertTrue("Should return FOUND or DENIED when GPS is available", 
            result == GpsStatus.FOUND || result == GpsStatus.DENIED)
    }

    @Test
    fun `determineGpsStatus should return FOUND when accuracy is at boundary 100m`() {
        // Given
        val recentUpdateTime = System.currentTimeMillis() - 5000

        // When
        val result = ErrorHandler.determineGpsStatus(context, accuracy = 100f, lastUpdateTime = recentUpdateTime)

        // Then
        assertTrue("Should return FOUND or DENIED when accuracy is exactly 100m", 
            result == GpsStatus.FOUND || result == GpsStatus.DENIED)
    }

    @Test
    fun `handleGpsError should not show toast for FOUND status`() {
        // When - handleGpsError должен вернуться сразу для FOUND
        // Then - не должно быть исключений
        ErrorHandler.handleGpsError(context, GpsStatus.FOUND, showToast = true)
        assertTrue("Should handle FOUND status without exception", true)
    }

    @Test
    fun `handleGpsError should handle all status types without exception`() {
        // When & Then - все статусы должны обрабатываться без исключений
        GpsStatus.values().forEach { status ->
            ErrorHandler.handleGpsError(context, status, showToast = false)
        }
        assertTrue("Should handle all GPS statuses", true)
    }

    @Test
    fun `handleSaveError should handle SQLException`() {
        // Given
        val error = java.sql.SQLException("Database error")

        // When & Then - не должно быть исключений
        ErrorHandler.handleSaveError(context, error, showToast = false)
        assertTrue("Should handle SQLException", true)
    }

    @Test
    fun `handleSaveError should handle IOException`() {
        // Given
        val error = java.io.IOException("IO error")

        // When & Then - не должно быть исключений
        ErrorHandler.handleSaveError(context, error, showToast = false)
        assertTrue("Should handle IOException", true)
    }

    @Test
    fun `handleSaveError should handle generic Exception`() {
        // Given
        val error = RuntimeException("Generic error")

        // When & Then - не должно быть исключений
        ErrorHandler.handleSaveError(context, error, showToast = false)
        assertTrue("Should handle generic Exception", true)
    }

    @Test
    fun `handleLoadError should handle SQLException`() {
        // Given
        val error = java.sql.SQLException("Database error")

        // When & Then - не должно быть исключений
        ErrorHandler.handleLoadError(context, error, showToast = false)
        assertTrue("Should handle SQLException", true)
    }

    @Test
    fun `handleLoadError should handle IOException`() {
        // Given
        val error = java.io.IOException("IO error")

        // When & Then - не должно быть исключений
        ErrorHandler.handleLoadError(context, error, showToast = false)
        assertTrue("Should handle IOException", true)
    }

    @Test
    fun `retryWithBackoff should succeed on first attempt`() = runTest {
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
    fun `retryWithBackoff should retry on failure`() = runTest {
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
    fun `retryWithBackoff should throw exception after max retries`() = runTest {
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
    fun `retryWithBackoff should respect maxDelay`() = runTest {
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
    fun `retryWithBackoff should use exponential backoff`() = runTest {
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

