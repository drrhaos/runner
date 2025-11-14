package com.example.runner.data

import org.junit.Test
import org.junit.Assert.*

/**
 * Тесты для TrackData
 */
class TrackDataTest {

    @Test
    fun `TrackData should be created with all parameters`() {
        // Given
        val points = listOf(
            TrackPoint(55.7558, 37.6176, 1000L, 10f, 5f, 150.0),
            TrackPoint(55.7560, 37.6178, 2000L, 10f, 5f, 150.0)
        )
        val totalDistance = 100f
        val totalDuration = 1000L
        val avgSpeed = 5f
        val maxSpeed = 10f
        val startTime = 1000L
        val endTime = 2000L

        // When
        val trackData = TrackData(
            points = points,
            totalDistance = totalDistance,
            totalDuration = totalDuration,
            avgSpeed = avgSpeed,
            maxSpeed = maxSpeed,
            startTime = startTime,
            endTime = endTime
        )

        // Then
        assertEquals(points, trackData.points)
        assertEquals(totalDistance, trackData.totalDistance, 0.01f)
        assertEquals(totalDuration, trackData.totalDuration)
        assertEquals(avgSpeed, trackData.avgSpeed, 0.01f)
        assertEquals(maxSpeed, trackData.maxSpeed, 0.01f)
        assertEquals(startTime, trackData.startTime)
        assertEquals(endTime, trackData.endTime)
    }

    @Test
    fun `TrackData should be created with empty points`() {
        // Given
        val points = emptyList<TrackPoint>()
        val totalDistance = 0f
        val totalDuration = 0L
        val avgSpeed = 0f
        val maxSpeed = 0f
        val startTime = 0L
        val endTime = 0L

        // When
        val trackData = TrackData(
            points = points,
            totalDistance = totalDistance,
            totalDuration = totalDuration,
            avgSpeed = avgSpeed,
            maxSpeed = maxSpeed,
            startTime = startTime,
            endTime = endTime
        )

        // Then
        assertTrue(trackData.points.isEmpty())
        assertEquals(0f, trackData.totalDistance, 0.01f)
        assertEquals(0L, trackData.totalDuration)
        assertEquals(0f, trackData.avgSpeed, 0.01f)
        assertEquals(0f, trackData.maxSpeed, 0.01f)
        assertEquals(0L, trackData.startTime)
        assertEquals(0L, trackData.endTime)
    }

    @Test
    fun `TrackData should handle single point`() {
        // Given
        val points = listOf(
            TrackPoint(55.7558, 37.6176, 1000L, 10f, 5f, 150.0)
        )
        val totalDistance = 0f
        val totalDuration = 0L
        val avgSpeed = 0f
        val maxSpeed = 5f
        val startTime = 1000L
        val endTime = 1000L

        // When
        val trackData = TrackData(
            points = points,
            totalDistance = totalDistance,
            totalDuration = totalDuration,
            avgSpeed = avgSpeed,
            maxSpeed = maxSpeed,
            startTime = startTime,
            endTime = endTime
        )

        // Then
        assertEquals(1, trackData.points.size)
        assertEquals(0f, trackData.totalDistance, 0.01f)
        assertEquals(0L, trackData.totalDuration)
        assertEquals(0f, trackData.avgSpeed, 0.01f)
        assertEquals(5f, trackData.maxSpeed, 0.01f)
        assertEquals(1000L, trackData.startTime)
        assertEquals(1000L, trackData.endTime)
    }

    @Test
    fun `TrackData should handle multiple points`() {
        // Given
        val points = listOf(
            TrackPoint(55.7558, 37.6176, 1000L, 10f, 5f, 150.0),
            TrackPoint(55.7560, 37.6178, 2000L, 10f, 5f, 150.0),
            TrackPoint(55.7562, 37.6180, 3000L, 10f, 5f, 150.0)
        )
        val totalDistance = 200f
        val totalDuration = 2000L
        val avgSpeed = 5f
        val maxSpeed = 10f
        val startTime = 1000L
        val endTime = 3000L

        // When
        val trackData = TrackData(
            points = points,
            totalDistance = totalDistance,
            totalDuration = totalDuration,
            avgSpeed = avgSpeed,
            maxSpeed = maxSpeed,
            startTime = startTime,
            endTime = endTime
        )

        // Then
        assertEquals(3, trackData.points.size)
        assertEquals(200f, trackData.totalDistance, 0.01f)
        assertEquals(2000L, trackData.totalDuration)
        assertEquals(5f, trackData.avgSpeed, 0.01f)
        assertEquals(10f, trackData.maxSpeed, 0.01f)
        assertEquals(1000L, trackData.startTime)
        assertEquals(3000L, trackData.endTime)
    }

    @Test
    fun `TrackData should handle zero values`() {
        // Given
        val points = emptyList<TrackPoint>()
        val totalDistance = 0f
        val totalDuration = 0L
        val avgSpeed = 0f
        val maxSpeed = 0f
        val startTime = 0L
        val endTime = 0L

        // When
        val trackData = TrackData(
            points = points,
            totalDistance = totalDistance,
            totalDuration = totalDuration,
            avgSpeed = avgSpeed,
            maxSpeed = maxSpeed,
            startTime = startTime,
            endTime = endTime
        )

        // Then
        assertTrue(trackData.points.isEmpty())
        assertEquals(0f, trackData.totalDistance, 0.01f)
        assertEquals(0L, trackData.totalDuration)
        assertEquals(0f, trackData.avgSpeed, 0.01f)
        assertEquals(0f, trackData.maxSpeed, 0.01f)
        assertEquals(0L, trackData.startTime)
        assertEquals(0L, trackData.endTime)
    }

    @Test
    fun `TrackData should handle large values`() {
        // Given
        val points = listOf(
            TrackPoint(55.7558, 37.6176, 1000L, 10f, 5f, 150.0)
        )
        val totalDistance = 1000f
        val totalDuration = 3600000L // 1 hour
        val avgSpeed = 10f
        val maxSpeed = 20f
        val startTime = 1000L
        val endTime = 3601000L

        // When
        val trackData = TrackData(
            points = points,
            totalDistance = totalDistance,
            totalDuration = totalDuration,
            avgSpeed = avgSpeed,
            maxSpeed = maxSpeed,
            startTime = startTime,
            endTime = endTime
        )

        // Then
        assertEquals(1, trackData.points.size)
        assertEquals(1000f, trackData.totalDistance, 0.01f)
        assertEquals(3600000L, trackData.totalDuration)
        assertEquals(10f, trackData.avgSpeed, 0.01f)
        assertEquals(20f, trackData.maxSpeed, 0.01f)
        assertEquals(1000L, trackData.startTime)
        assertEquals(3601000L, trackData.endTime)
    }
}
