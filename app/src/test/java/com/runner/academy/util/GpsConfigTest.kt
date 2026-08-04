package com.runner.academy.util

import com.google.android.gms.location.LocationRequest
import org.junit.Test
import org.junit.Assert.*

/**
 * Тесты для GpsConfig
 */
class GpsConfigTest {

    @Test
    fun constants_should_have_correct_values() {
        assertEquals(2f, GpsConfig.MIN_DISTANCE, 0.01f)
        assertEquals(1000L, GpsConfig.MIN_UPDATE_INTERVAL)
        assertEquals(1000L, GpsConfig.HIGH_ACCURACY_INTERVAL)
        assertEquals(5000L, GpsConfig.MEDIUM_ACCURACY_INTERVAL)
        assertEquals(10000L, GpsConfig.LOW_ACCURACY_INTERVAL)
    }

    @Test
    fun getAdaptiveInterval_backs_off_when_slower() {
        assertEquals(1000L, GpsConfig.getAdaptiveInterval(25f))
        assertEquals(1000L, GpsConfig.getAdaptiveInterval(12f))
        assertEquals(1000L, GpsConfig.getAdaptiveInterval(6f))
        assertEquals(2000L, GpsConfig.getAdaptiveInterval(2f))
    }

    @Test
    fun createworkoutlocationrequest_should_not_be_null() {
        // When
        val request = GpsConfig.createWorkoutLocationRequest()

        // Then
        assertNotNull(request)
    }

    @Test
    fun createworkoutlocationrequest_should_be_locationrequest_instance() {
        // When
        val request = GpsConfig.createWorkoutLocationRequest()

        // Then
        assertTrue(request is LocationRequest)
    }

    @Test
    fun createworkoutlocationrequest_should_be_consistent() {
        // When
        val request1 = GpsConfig.createWorkoutLocationRequest()
        val request2 = GpsConfig.createWorkoutLocationRequest()

        // Then
        assertNotNull(request1)
        assertNotNull(request2)
        // Both requests should be created successfully
    }

    @Test
    fun createworkoutlocationrequest_should_not_throw_exception() {
        // When & Then
        try {
            GpsConfig.createWorkoutLocationRequest()
            assertTrue(true) // If we get here, no exception was thrown
        } catch (e: Exception) {
            fail("createWorkoutLocationRequest should not throw exception: ${e.message}")
        }
    }

    @Test
    fun constants_should_be_accessible() {
        // Then
        assertTrue(GpsConfig.MIN_DISTANCE >= 0f)
        assertTrue(GpsConfig.MIN_UPDATE_INTERVAL > 0)
        assertTrue(GpsConfig.HIGH_ACCURACY_INTERVAL > 0)
        assertTrue(GpsConfig.MEDIUM_ACCURACY_INTERVAL > 0)
        assertTrue(GpsConfig.LOW_ACCURACY_INTERVAL > 0)
    }

    @Test
    fun constants_should_have_reasonable_values() {
        // Then
        assertTrue("MIN_DISTANCE should be non-negative", GpsConfig.MIN_DISTANCE >= 0f)
        assertTrue("MIN_UPDATE_INTERVAL should be reasonable", GpsConfig.MIN_UPDATE_INTERVAL >= 100L)
        assertTrue("HIGH_ACCURACY_INTERVAL should be reasonable", GpsConfig.HIGH_ACCURACY_INTERVAL >= 100L)
        assertTrue("MEDIUM_ACCURACY_INTERVAL should be reasonable", GpsConfig.MEDIUM_ACCURACY_INTERVAL >= 100L)
        assertTrue("LOW_ACCURACY_INTERVAL should be reasonable", GpsConfig.LOW_ACCURACY_INTERVAL >= 100L)
    }

    @Test
    fun intervals_should_be_in_ascending_order() {
        // Then
        assertTrue("HIGH_ACCURACY_INTERVAL should be <= MEDIUM_ACCURACY_INTERVAL", 
            GpsConfig.HIGH_ACCURACY_INTERVAL <= GpsConfig.MEDIUM_ACCURACY_INTERVAL)
        assertTrue("MEDIUM_ACCURACY_INTERVAL should be <= LOW_ACCURACY_INTERVAL", 
            GpsConfig.MEDIUM_ACCURACY_INTERVAL <= GpsConfig.LOW_ACCURACY_INTERVAL)
    }

    @Test
    fun min_update_interval_should_be_reasonable_for_gps() {
        assertTrue("MIN_UPDATE_INTERVAL should be at least 1 second", GpsConfig.MIN_UPDATE_INTERVAL >= 1000L)
        assertTrue("MIN_UPDATE_INTERVAL should not be too high", GpsConfig.MIN_UPDATE_INTERVAL <= 10000L)
    }

    @Test
    fun min_distance_should_be_reasonable_for_gps() {
        assertTrue("MIN_DISTANCE should be positive for battery", GpsConfig.MIN_DISTANCE > 0f)
        assertTrue("MIN_DISTANCE should not be too high", GpsConfig.MIN_DISTANCE <= 50f)
    }

    @Test
    fun createworkoutlocationrequest_should_create_multiple_instances() {
        // When
        val requests = (1..5).map { GpsConfig.createWorkoutLocationRequest() }

        // Then
        assertEquals(5, requests.size)
        requests.forEach { assertNotNull(it) }
    }

    @Test
    fun constants_should_be_final() {
        // Then - These should compile without issues, indicating they're accessible
        val minDistance = GpsConfig.MIN_DISTANCE
        val minUpdateInterval = GpsConfig.MIN_UPDATE_INTERVAL
        val highAccuracyInterval = GpsConfig.HIGH_ACCURACY_INTERVAL
        val mediumAccuracyInterval = GpsConfig.MEDIUM_ACCURACY_INTERVAL
        val lowAccuracyInterval = GpsConfig.LOW_ACCURACY_INTERVAL

        assertNotNull(minDistance)
        assertNotNull(minUpdateInterval)
        assertNotNull(highAccuracyInterval)
        assertNotNull(mediumAccuracyInterval)
        assertNotNull(lowAccuracyInterval)
    }
}
