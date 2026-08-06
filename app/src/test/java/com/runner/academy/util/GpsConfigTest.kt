package com.runner.academy.util

import com.google.android.gms.location.LocationRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Тесты для GpsConfig
 */
class GpsConfigTest {

    @Test
    fun constants_should_have_correct_values() {
        assertEquals(2f, GpsConfig.MIN_DISTANCE, 0.01f)
        assertEquals(3f, GpsConfig.MIN_DISTANCE_SCREEN_OFF, 0.01f)
        assertEquals(1000L, GpsConfig.MIN_UPDATE_INTERVAL)
        assertEquals(1000L, GpsConfig.HIGH_ACCURACY_INTERVAL)
        assertEquals(2000L, GpsConfig.SCREEN_OFF_INTERVAL)
        assertEquals(20_000L, GpsConfig.SCREEN_OFF_MAX_UPDATE_DELAY_MS)
        assertEquals(5000L, GpsConfig.MEDIUM_ACCURACY_INTERVAL)
        assertEquals(10000L, GpsConfig.LOW_ACCURACY_INTERVAL)
    }

    @Test
    fun getAdaptiveInterval_screenOn_backs_off_when_slower() {
        assertEquals(1000L, GpsConfig.getAdaptiveInterval(25f, screenInteractive = true))
        assertEquals(1000L, GpsConfig.getAdaptiveInterval(12f, screenInteractive = true))
        assertEquals(1000L, GpsConfig.getAdaptiveInterval(6f, screenInteractive = true))
        assertEquals(2000L, GpsConfig.getAdaptiveInterval(2f, screenInteractive = true))
    }

    @Test
    fun getAdaptiveInterval_screenOff_uses_two_second_base() {
        assertEquals(2000L, GpsConfig.getAdaptiveInterval(12f, screenInteractive = false))
        assertEquals(3000L, GpsConfig.getAdaptiveInterval(2f, screenInteractive = false))
        assertEquals(1000L, GpsConfig.getAdaptiveInterval(25f, screenInteractive = false))
    }

    @Test
    fun getAdaptiveInterval_turning_densifies() {
        assertEquals(
            1000L,
            GpsConfig.getAdaptiveInterval(12f, screenInteractive = false, turning = true)
        )
    }

    @Test
    fun bearingDelta_handles_wraparound() {
        assertEquals(20f, GpsConfig.bearingDeltaDegrees(10f, 30f), 0.01f)
        assertEquals(20f, GpsConfig.bearingDeltaDegrees(350f, 10f), 0.01f)
        assertFalse(GpsConfig.isTurning(0f, 10f))
        assertTrue(GpsConfig.isTurning(0f, 25f))
    }

    @Test
    fun createWorkoutLocationRequest_should_not_be_null() {
        assertNotNull(GpsConfig.createWorkoutLocationRequest(screenInteractive = true))
        assertNotNull(GpsConfig.createWorkoutLocationRequest(screenInteractive = false))
    }

    @Test
    fun createWorkoutLocationRequest_should_be_locationrequest_instance() {
        assertTrue(GpsConfig.createWorkoutLocationRequest() is LocationRequest)
    }

    @Test
    fun constants_should_be_accessible() {
        assertTrue(GpsConfig.MIN_DISTANCE >= 0f)
        assertTrue(GpsConfig.MIN_UPDATE_INTERVAL > 0)
        assertTrue(GpsConfig.HIGH_ACCURACY_INTERVAL > 0)
        assertTrue(GpsConfig.MEDIUM_ACCURACY_INTERVAL > 0)
        assertTrue(GpsConfig.LOW_ACCURACY_INTERVAL > 0)
    }

    @Test
    fun intervals_should_be_in_ascending_order() {
        assertTrue(GpsConfig.HIGH_ACCURACY_INTERVAL <= GpsConfig.SCREEN_OFF_INTERVAL)
        assertTrue(GpsConfig.HIGH_ACCURACY_INTERVAL <= GpsConfig.MEDIUM_ACCURACY_INTERVAL)
        assertTrue(GpsConfig.MEDIUM_ACCURACY_INTERVAL <= GpsConfig.LOW_ACCURACY_INTERVAL)
    }

    @Test
    fun min_update_interval_should_be_reasonable_for_gps() {
        assertTrue(GpsConfig.MIN_UPDATE_INTERVAL >= 1000L)
        assertTrue(GpsConfig.MIN_UPDATE_INTERVAL <= 10000L)
    }

    @Test
    fun min_distance_should_be_reasonable_for_gps() {
        assertTrue(GpsConfig.MIN_DISTANCE > 0f)
        assertTrue(GpsConfig.MIN_DISTANCE <= 50f)
        assertTrue(GpsConfig.MIN_DISTANCE_SCREEN_OFF >= GpsConfig.MIN_DISTANCE)
    }
}
