package com.runner.academy.util

import androidx.core.location.LocationRequestCompat
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
    fun getAdaptiveInterval_turning_densifies_only_with_screen_on() {
        assertEquals(
            1000L,
            GpsConfig.getAdaptiveInterval(2f, screenInteractive = true, turning = true)
        )
        // Screen-off fixes are batched: turn seen after the fact, keep the base cadence
        assertEquals(
            2000L,
            GpsConfig.getAdaptiveInterval(12f, screenInteractive = false, turning = true)
        )
    }

    @Test
    fun turnDetector_enters_on_sharp_turn_and_releases_after_straight_fixes() {
        val detector = GpsConfig.TurnDetector()
        assertFalse(detector.onFix(0f, 3f))
        assertTrue(detector.onFix(40f, 3f))
        // Hysteresis: stays in turn for TURN_RELEASE_FIXES - 1 straight fixes
        repeat(GpsConfig.TURN_RELEASE_FIXES - 1) { assertTrue(detector.onFix(40f, 3f)) }
        assertFalse(detector.onFix(40f, 3f))
    }

    @Test
    fun turnDetector_ignores_bearing_at_low_speed() {
        val detector = GpsConfig.TurnDetector()
        assertFalse(detector.onFix(0f, 0.5f))
        assertFalse(detector.onFix(90f, 0.5f))
        assertFalse(detector.onFix(180f, 3f)) // no comparable previous bearing
    }

    @Test
    fun screenOff_lost_timeout_exceeds_batch_delay() {
        // Otherwise the watchdog treats a normal pending batch as a GPS outage
        assertTrue(
            com.runner.academy.service.WorkoutTrackingService.NO_LOCATION_UPDATE_TIMEOUT_SCREEN_OFF_MS >
                GpsConfig.SCREEN_OFF_MAX_UPDATE_DELAY_MS
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
    fun createWorkoutLocationRequest_screenOn_is_dense_with_short_batch() {
        val request = GpsConfig.createWorkoutLocationRequest(screenInteractive = true)
        assertEquals(LocationRequestCompat.QUALITY_HIGH_ACCURACY, request.quality)
        assertEquals(GpsConfig.HIGH_ACCURACY_INTERVAL, request.intervalMillis)
        assertEquals(GpsConfig.MIN_DISTANCE, request.minUpdateDistanceMeters, 0.01f)
        assertEquals(GpsConfig.SCREEN_ON_MAX_UPDATE_DELAY_MS, request.maxUpdateDelayMillis)
    }

    @Test
    fun createWorkoutLocationRequest_screenOff_batches_for_battery() {
        val request = GpsConfig.createWorkoutLocationRequest(screenInteractive = false)
        assertEquals(LocationRequestCompat.QUALITY_HIGH_ACCURACY, request.quality)
        assertEquals(GpsConfig.SCREEN_OFF_INTERVAL, request.intervalMillis)
        assertEquals(GpsConfig.MIN_DISTANCE_SCREEN_OFF, request.minUpdateDistanceMeters, 0.01f)
        assertEquals(GpsConfig.SCREEN_OFF_MAX_UPDATE_DELAY_MS, request.maxUpdateDelayMillis)
    }

    @Test
    fun createAdaptiveLocationRequest_never_goes_below_min_interval() {
        val request = GpsConfig.createAdaptiveLocationRequest(intervalMs = 200L)
        assertEquals(GpsConfig.MIN_UPDATE_INTERVAL, request.intervalMillis)
        assertEquals(GpsConfig.MIN_UPDATE_INTERVAL, request.minUpdateIntervalMillis)
    }

    @Test
    fun createPreWorkoutLocationRequest_keeps_updating_while_standing() {
        val request = GpsConfig.createPreWorkoutLocationRequest()
        assertEquals(LocationRequestCompat.QUALITY_HIGH_ACCURACY, request.quality)
        assertEquals(0f, request.minUpdateDistanceMeters, 0.01f)
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
