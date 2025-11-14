package com.example.runner.util

import org.junit.Test
import org.junit.Assert.*

/**
 * Простые тесты для GpsConfig
 */
class SimpleGpsConfigTest {

    @Test
    fun `constants should have correct values`() {
        // Then
        assertEquals(5000L, GpsConfig.MEDIUM_ACCURACY_INTERVAL)
        assertEquals(5f, GpsConfig.MIN_DISTANCE, 0.1f)
        assertEquals(2000L, GpsConfig.HIGH_ACCURACY_INTERVAL)
        assertEquals(10000L, GpsConfig.LOW_ACCURACY_INTERVAL)
    }

    @Test
    fun `createWorkoutLocationRequest should not be null`() {
        // When
        val locationRequest = GpsConfig.createWorkoutLocationRequest()

        // Then
        assertNotNull(locationRequest)
    }
}
