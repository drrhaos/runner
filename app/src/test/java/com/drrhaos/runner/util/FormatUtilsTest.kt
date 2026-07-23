package com.drrhaos.runner.util

import org.junit.Test
import org.junit.Assert.*

/**
 * Тесты для FormatUtils
 */
class FormatUtilsTest {

    @Test
    fun format_time_should_format_milliseconds_correctly() {
        // Given
        val time1 = 61000L // 1 minute 1 second
        val time2 = 3661000L // 1 hour 1 minute 1 second
        val time3 = 0L // 0 seconds
        val time4 = 30000L // 30 seconds

        // When
        val result1 = FormatUtils.formatTime(time1)
        val result2 = FormatUtils.formatTime(time2)
        val result3 = FormatUtils.formatTime(time3)
        val result4 = FormatUtils.formatTime(time4)

        // Then
        assertEquals("01:01", result1)
        assertEquals("1:01:01", result2)
        assertEquals("00:00", result3)
        assertEquals("00:30", result4)
    }

    @Test
    fun format_speed_should_format_speed_correctly() {
        // Given
        val speed1 = 5.0f // 5 m/s
        val speed2 = 0.0f // 0 m/s

        // When
        val result1 = FormatUtils.formatSpeed(speed1)
        val result2 = FormatUtils.formatSpeed(speed2)

        // Then
        assertTrue(result1.contains("км/ч"))
        assertTrue(result2.contains("км/ч"))
    }

    @Test
    fun format_pace_should_format_pace_correctly() {
        // Given
        val pace1 = 5.0f // 5 minutes per km
        val pace2 = 0.0f // 0 minutes per km

        // When
        val result1 = FormatUtils.formatPace(pace1)
        val result2 = FormatUtils.formatPace(pace2)

        // Then
        assertTrue(result1.contains("/км"))
        assertTrue(result2.contains("/км"))
    }

    @Test
    fun format_distance_should_format_distance_correctly() {
        // Given
        val distance1 = 5.5f // 5.5 km
        val distance2 = 0.0f // 0 km
        val distance3 = 1000.0f // 1000 km

        // When
        val result1 = FormatUtils.formatDistance(distance1)
        val result2 = FormatUtils.formatDistance(distance2)
        val result3 = FormatUtils.formatDistance(distance3)

        // Then
        assertEquals("5.50 км", result1)
        assertEquals("0.00 км", result2)
        assertEquals("1000.00 км", result3)
    }

    @Test
    fun format_calories_should_format_calories_correctly() {
        // Given
        val calories1 = 300 // 300 calories
        val calories2 = 0 // 0 calories
        val calories3 = 1500 // 1500 calories

        // When
        val result1 = FormatUtils.formatCalories(calories1)
        val result2 = FormatUtils.formatCalories(calories2)
        val result3 = FormatUtils.formatCalories(calories3)

        // Then
        assertEquals("300 ккал", result1)
        assertEquals("0 ккал", result2)
        assertEquals("1500 ккал", result3)
    }

    @Test
    fun calculateaveragespeed_should_calculate_correctly() {
        // Given
        val distance1 = 10.0f // 10 km
        val time1 = 3600000L // 1 hour in milliseconds
        val distance2 = 5.0f // 5 km
        val time2 = 1800000L // 30 minutes in milliseconds
        val distance3 = 0.0f // 0 km
        val time3 = 3600000L // 1 hour

        // When
        val result1 = FormatUtils.calculateAverageSpeed(distance1, time1)
        val result2 = FormatUtils.calculateAverageSpeed(distance2, time2)
        val result3 = FormatUtils.calculateAverageSpeed(distance3, time3)

        // Then
        assertEquals(10.0f, result1, 0.1f)
        assertEquals(10.0f, result2, 0.1f)
        assertEquals(0.0f, result3, 0.1f)
    }

    @Test
    fun calculateaveragespeed_should_handle_zero_time() {
        // Given
        val distance = 10.0f
        val time = 0L

        // When
        val result = FormatUtils.calculateAverageSpeed(distance, time)

        // Then
        assertEquals(0.0f, result, 0.1f)
    }

    @Test
    fun calculateaveragespeed_should_handle_negative_distance() {
        // Given
        val distance = -5.0f
        val time = 3600000L

        // When
        val result = FormatUtils.calculateAverageSpeed(distance, time)

        // Then
        // Just check that method doesn't crash
        assertNotNull(result)
    }

    @Test
    fun format_time_should_handle_large_values() {
        // Given
        val time = 3661000L // 1 hour 1 minute 1 second

        // When
        val result = FormatUtils.formatTime(time)

        // Then
        assertEquals("1:01:01", result)
    }

    @Test
    fun format_time_should_handle_very_large_values() {
        // Given
        val time = 36610000L // 10 hours 10 minutes 10 seconds

        // When
        val result = FormatUtils.formatTime(time)

        // Then
        assertEquals("10:10:10", result)
    }

    @Test
    fun format_speed_should_handle_very_high_speeds() {
        // Given
        val speed = 100.0f // 100 m/s

        // When
        val result = FormatUtils.formatSpeed(speed)

        // Then
        assertTrue(result.contains("км/ч"))
    }

    @Test
    fun format_pace_should_handle_very_slow_paces() {
        // Given
        val pace = 60.0f // 60 minutes per km

        // When
        val result = FormatUtils.formatPace(pace)

        // Then
        assertEquals("60:00 /км", result)
    }

    @Test
    fun format_distance_should_handle_very_small_distances() {
        // Given
        val distance = 0.001f // 0.001 km

        // When
        val result = FormatUtils.formatDistance(distance)

        // Then
        assertEquals("0.00 км", result)
    }

    @Test
    fun format_calories_should_handle_decimal_values() {
        // Given
        val calories = 123 // 123 calories

        // When
        val result = FormatUtils.formatCalories(calories)

        // Then
        assertEquals("123 ккал", result)
    }

    @Test
    fun `calculatePaceKph delegates to SpeedPaceCalculator`() {
        assertEquals(
            5f,
            FormatUtils.calculatePaceKph(10f, 50 * 60_000L),
            0.01f
        )
    }

    @Test
    fun `calculatePaceMph returns min per mile`() {
        val paceMph = FormatUtils.calculatePaceMph(
            SpeedPaceCalculator.KM_PER_MILE,
            5 * 60_000L
        )
        assertEquals(5f, paceMph, 0.01f)
    }
}
