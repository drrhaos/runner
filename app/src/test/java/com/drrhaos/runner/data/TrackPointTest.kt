package com.drrhaos.runner.data

import org.junit.Test
import org.junit.Assert.*

/**
 * Тесты для TrackPoint
 */
class TrackPointTest {

    @Test
    fun `TrackPoint should be created with all parameters`() {
        // Given
        val latitude = 55.7558
        val longitude = 37.6176
        val timestamp = 1000L
        val accuracy = 10f
        val speed = 5f
        val altitude = 150.0

        // When
        val trackPoint = TrackPoint(
            latitude = latitude,
            longitude = longitude,
            timestamp = timestamp,
            accuracy = accuracy,
            speed = speed,
            altitude = altitude
        )

        // Then
        assertEquals(latitude, trackPoint.latitude, 0.0001)
        assertEquals(longitude, trackPoint.longitude, 0.0001)
        assertEquals(timestamp, trackPoint.timestamp)
        assertEquals(accuracy, trackPoint.accuracy)
        assertEquals(speed, trackPoint.speed)
        assertEquals(altitude, trackPoint.altitude)
    }

    @Test
    fun `TrackPoint should be created with null optional parameters`() {
        // Given
        val latitude = 55.7558
        val longitude = 37.6176
        val timestamp = 1000L

        // When
        val trackPoint = TrackPoint(
            latitude = latitude,
            longitude = longitude,
            timestamp = timestamp,
            accuracy = null,
            speed = null,
            altitude = null
        )

        // Then
        assertEquals(latitude, trackPoint.latitude, 0.0001)
        assertEquals(longitude, trackPoint.longitude, 0.0001)
        assertEquals(timestamp, trackPoint.timestamp)
        assertNull(trackPoint.accuracy)
        assertNull(trackPoint.speed)
        assertNull(trackPoint.altitude)
    }

    @Test
    fun `TrackPoint should be created with some null parameters`() {
        // Given
        val latitude = 55.7558
        val longitude = 37.6176
        val timestamp = 1000L
        val accuracy = 10f

        // When
        val trackPoint = TrackPoint(
            latitude = latitude,
            longitude = longitude,
            timestamp = timestamp,
            accuracy = accuracy,
            speed = null,
            altitude = null
        )

        // Then
        assertEquals(latitude, trackPoint.latitude, 0.0001)
        assertEquals(longitude, trackPoint.longitude, 0.0001)
        assertEquals(timestamp, trackPoint.timestamp)
        assertEquals(accuracy, trackPoint.accuracy)
        assertNull(trackPoint.speed)
        assertNull(trackPoint.altitude)
    }

    @Test
    fun `TrackPoint should handle extreme values`() {
        // Given
        val latitude = 90.0
        val longitude = 180.0
        val timestamp = Long.MAX_VALUE
        val accuracy = Float.MAX_VALUE
        val speed = Float.MAX_VALUE
        val altitude = Double.MAX_VALUE

        // When
        val trackPoint = TrackPoint(
            latitude = latitude,
            longitude = longitude,
            timestamp = timestamp,
            accuracy = accuracy,
            speed = speed,
            altitude = altitude
        )

        // Then
        assertEquals(latitude, trackPoint.latitude, 0.0001)
        assertEquals(longitude, trackPoint.longitude, 0.0001)
        assertEquals(timestamp, trackPoint.timestamp)
        assertEquals(accuracy, trackPoint.accuracy)
        assertEquals(speed, trackPoint.speed)
        assertEquals(altitude, trackPoint.altitude)
    }

    @Test
    fun `TrackPoint should handle negative values`() {
        // Given
        val latitude = -90.0
        val longitude = -180.0
        val timestamp = 0L
        val accuracy = 0f
        val speed = 0f
        val altitude = 0.0

        // When
        val trackPoint = TrackPoint(
            latitude = latitude,
            longitude = longitude,
            timestamp = timestamp,
            accuracy = accuracy,
            speed = speed,
            altitude = altitude
        )

        // Then
        assertEquals(latitude, trackPoint.latitude, 0.0001)
        assertEquals(longitude, trackPoint.longitude, 0.0001)
        assertEquals(timestamp, trackPoint.timestamp)
        assertEquals(accuracy, trackPoint.accuracy)
        assertEquals(speed, trackPoint.speed)
        assertEquals(altitude, trackPoint.altitude)
    }

    @Test
    fun `TrackPoint should be created with zero values`() {
        // Given
        val latitude = 0.0
        val longitude = 0.0
        val timestamp = 0L
        val accuracy = 0f
        val speed = 0f
        val altitude = 0.0

        // When
        val trackPoint = TrackPoint(
            latitude = latitude,
            longitude = longitude,
            timestamp = timestamp,
            accuracy = accuracy,
            speed = speed,
            altitude = altitude
        )

        // Then
        assertEquals(latitude, trackPoint.latitude, 0.0001)
        assertEquals(longitude, trackPoint.longitude, 0.0001)
        assertEquals(timestamp, trackPoint.timestamp)
        assertEquals(accuracy, trackPoint.accuracy)
        assertEquals(speed, trackPoint.speed)
        assertEquals(altitude, trackPoint.altitude)
    }

    @Test
    fun `TrackPoint should handle decimal values`() {
        // Given
        val latitude = 55.7558
        val longitude = 37.6176
        val timestamp = 1000L
        val accuracy = 10.5f
        val speed = 5.7f
        val altitude = 150.25

        // When
        val trackPoint = TrackPoint(
            latitude = latitude,
            longitude = longitude,
            timestamp = timestamp,
            accuracy = accuracy,
            speed = speed,
            altitude = altitude
        )

        // Then
        assertEquals(latitude, trackPoint.latitude, 0.0001)
        assertEquals(longitude, trackPoint.longitude, 0.0001)
        assertEquals(timestamp, trackPoint.timestamp)
        assertEquals(accuracy, trackPoint.accuracy)
        assertEquals(speed, trackPoint.speed)
        assertEquals(altitude, trackPoint.altitude)
    }
}
