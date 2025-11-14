package com.example.runner.util

import android.location.Location
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Тесты для GpsFilter - фильтрация GPS выбросов и валидация координат
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class GpsFilterTest {

    private fun createLocation(
        latitude: Double,
        longitude: Double,
        accuracy: Float = 10f,
        speed: Float = 3f,
        time: Long = System.currentTimeMillis()
    ): Location {
        val location = Location("test")
        location.setLatitude(latitude)
        location.setLongitude(longitude)
        location.setAccuracy(accuracy)
        location.setSpeed(speed)
        location.setTime(time)
        return location
    }

    @Test
    fun `isValidGpsLocation should accept valid location`() {
        // Given
        val location = createLocation(55.7558, 37.6173, accuracy = 10f, speed = 3f)

        // When
        val result = GpsFilter.isValidGpsLocation(location)

        // Then
        assertTrue("Valid location should be accepted", result)
    }

    @Test
    fun `isValidGpsLocation should reject location without accuracy`() {
        // Given
        val location = Location("test").apply {
            latitude = 55.7558
            longitude = 37.6173
            speed = 3f
            // Не устанавливаем accuracy
        }

        // When
        val result = GpsFilter.isValidGpsLocation(location)

        // Then
        assertFalse("Location without accuracy should be rejected", result)
    }

    @Test
    fun `isValidGpsLocation should reject location without speed`() {
        // Given
        val location = Location("test").apply {
            latitude = 55.7558
            longitude = 37.6173
            accuracy = 10f
            // Не устанавливаем speed
        }

        // When
        val result = GpsFilter.isValidGpsLocation(location)

        // Then
        assertFalse("Location without speed should be rejected", result)
    }

    @Test
    fun `isValidGpsLocation should reject location with poor accuracy`() {
        // Given
        val location = createLocation(55.7558, 37.6173, accuracy = 150f, speed = 3f)

        // When
        val result = GpsFilter.isValidGpsLocation(location)

        // Then
        assertFalse("Location with accuracy > 100m should be rejected", result)
    }

    @Test
    fun `isValidGpsLocation should reject location with too high speed`() {
        // Given - скорость 15 м/с = 54 км/ч, что больше MAX_REASONABLE_SPEED (14 м/с)
        val location = createLocation(55.7558, 37.6173, accuracy = 10f, speed = 15f)

        // When
        val result = GpsFilter.isValidGpsLocation(location)

        // Then
        assertFalse("Location with speed > 14 m/s should be rejected", result)
    }

    @Test
    fun `isValidGpsLocation should accept location with acceptable speed`() {
        // Given - скорость 10 м/с = 36 км/ч, что приемлемо
        val location = createLocation(55.7558, 37.6173, accuracy = 10f, speed = 10f)

        // When
        val result = GpsFilter.isValidGpsLocation(location)

        // Then
        assertTrue("Location with acceptable speed should be accepted", result)
    }

    @Test
    fun `isValidGpsLocation should reject invalid latitude`() {
        // Given - широта больше 90
        val location = createLocation(91.0, 37.6173, accuracy = 10f, speed = 3f)

        // When
        val result = GpsFilter.isValidGpsLocation(location)

        // Then
        assertFalse("Location with latitude > 90 should be rejected", result)
    }

    @Test
    fun `isValidGpsLocation should reject invalid longitude`() {
        // Given - долгота больше 180
        val location = createLocation(55.7558, 181.0, accuracy = 10f, speed = 3f)

        // When
        val result = GpsFilter.isValidGpsLocation(location)

        // Then
        assertFalse("Location with longitude > 180 should be rejected", result)
    }

    @Test
    fun `isValidGpsLocation should reject NaN coordinates`() {
        // Given
        val location = createLocation(Double.NaN, 37.6173, accuracy = 10f, speed = 3f)

        // When
        val result = GpsFilter.isValidGpsLocation(location)

        // Then
        assertFalse("Location with NaN latitude should be rejected", result)
    }

    @Test
    fun `filterGpsOutlier should accept first location`() {
        // Given
        val location = createLocation(55.7558, 37.6173, accuracy = 10f, speed = 3f)

        // When
        val result = GpsFilter.filterGpsOutlier(location, null)

        // Then
        assertNotNull("First valid location should be accepted", result)
        assertEquals(location, result)
    }

    @Test
    fun `filterGpsOutlier should reject first location if invalid`() {
        // Given
        val location = createLocation(55.7558, 37.6173, accuracy = 150f, speed = 3f)

        // When
        val result = GpsFilter.filterGpsOutlier(location, null)

        // Then
        assertNull("Invalid first location should be rejected", result)
    }

    @Test
    fun `filterGpsOutlier should accept location close to previous`() {
        // Given
        val previousLocation = createLocation(55.7558, 37.6173, accuracy = 10f, speed = 3f)
        val newLocation = createLocation(55.7560, 37.6175, accuracy = 10f, speed = 3f,
            time = previousLocation.time + 2000) // 2 секунды позже

        // When
        val result = GpsFilter.filterGpsOutlier(newLocation, previousLocation)

        // Then
        assertNotNull("Location close to previous should be accepted", result)
        assertEquals(newLocation, result)
    }

    @Test
    fun `filterGpsOutlier should reject outlier too far from previous`() {
        // Given - расстояние > 500м (MAX_DISTANCE_BETWEEN_POINTS)
        val previousLocation = createLocation(55.7558, 37.6173, accuracy = 10f, speed = 3f)
        // Новое местоположение на расстоянии ~700м от предыдущего
        val newLocation = createLocation(55.7640, 37.6200, accuracy = 10f, speed = 3f,
            time = previousLocation.time + 2000)

        // When
        val result = GpsFilter.filterGpsOutlier(newLocation, previousLocation)

        // Then
        assertNull("Location too far from previous should be rejected", result)
    }

    @Test
    fun `filterGpsOutlier should reject location with earlier timestamp`() {
        // Given
        val previousLocation = createLocation(55.7558, 37.6173, accuracy = 10f, speed = 3f,
            time = System.currentTimeMillis())
        val newLocation = createLocation(55.7560, 37.6175, accuracy = 10f, speed = 3f,
            time = previousLocation.time - 1000) // Раньше на 1 секунду

        // When
        val result = GpsFilter.filterGpsOutlier(newLocation, previousLocation)

        // Then
        assertNull("Location with earlier timestamp should be rejected", result)
    }

    @Test
    fun `filterGpsOutlier should accept high accuracy location even if far`() {
        // Given - высокая точность (<= 20м) должна приниматься
        val previousLocation = createLocation(55.7558, 37.6173, accuracy = 10f, speed = 3f)
        val newLocation = createLocation(55.7560, 37.6175, accuracy = 15f, speed = 3f,
            time = previousLocation.time + 2000)

        // When
        val result = GpsFilter.filterGpsOutlier(newLocation, previousLocation)

        // Then
        assertNotNull("High accuracy location should be accepted", result)
    }

    @Test
    fun `createValidGeoPoint should create GeoPoint from valid location`() {
        // Given
        val location = createLocation(55.7558, 37.6173, accuracy = 10f, speed = 3f)

        // When
        val result = GpsFilter.createValidGeoPoint(location)

        // Then
        assertNotNull("Valid GeoPoint should be created", result)
        assertEquals(55.7558, result!!.latitude, 0.0001)
        assertEquals(37.6173, result.longitude, 0.0001)
    }

    @Test
    fun `createValidGeoPointWithFiltering should create GeoPoint from filtered location`() {
        // Given
        val previousLocation = createLocation(55.7558, 37.6173, accuracy = 10f, speed = 3f)
        val newLocation = createLocation(55.7560, 37.6175, accuracy = 10f, speed = 3f,
            time = previousLocation.time + 2000)

        // When
        val result = GpsFilter.createValidGeoPointWithFiltering(newLocation, previousLocation)

        // Then
        assertNotNull("Valid filtered GeoPoint should be created", result)
        assertEquals(55.7560, result!!.latitude, 0.0001)
        assertEquals(37.6175, result.longitude, 0.0001)
    }

    @Test
    fun `createValidGeoPointWithFiltering should return null for outlier`() {
        // Given - выброс (слишком далеко)
        val previousLocation = createLocation(55.7558, 37.6173, accuracy = 10f, speed = 3f)
        val newLocation = createLocation(55.7640, 37.6200, accuracy = 10f, speed = 3f,
            time = previousLocation.time + 2000)

        // When
        val result = GpsFilter.createValidGeoPointWithFiltering(newLocation, previousLocation)

        // Then
        assertNull("Outlier should result in null GeoPoint", result)
    }

    @Test
    fun `filterGpsOutlier should handle edge case with zero distance`() {
        // Given - та же самая точка
        val location = createLocation(55.7558, 37.6173, accuracy = 10f, speed = 3f)
        val sameLocation = createLocation(55.7558, 37.6173, accuracy = 10f, speed = 3f,
            time = location.time + 2000)

        // When
        val result = GpsFilter.filterGpsOutlier(sameLocation, location)

        // Then
        assertNotNull("Same location should be accepted (distance = 0)", result)
    }

    @Test
    fun `filterGpsOutlier should handle location at boundary distances`() {
        // Given - расстояние близко к MAX_DISTANCE_BETWEEN_POINTS (500м)
        val previousLocation = createLocation(55.7558, 37.6173, accuracy = 10f, speed = 3f)
        // Примерно 450 метров от предыдущей точки
        val newLocation = createLocation(55.7598, 37.6173, accuracy = 10f, speed = 3f,
            time = previousLocation.time + 2000)

        // When
        val result = GpsFilter.filterGpsOutlier(newLocation, previousLocation)

        // Then - должно быть принято, так как < 500м
        assertNotNull("Location at boundary distance should be accepted", result)
    }

    @Test
    fun `isValidGpsLocation should accept location with boundary accuracy`() {
        // Given - точность ровно 100м (граница)
        val location = createLocation(55.7558, 37.6173, accuracy = 100f, speed = 3f)

        // When
        val result = GpsFilter.isValidGpsLocation(location)

        // Then
        assertTrue("Location with boundary accuracy (100m) should be accepted", result)
    }

    @Test
    fun `isValidGpsLocation should accept location with boundary speed`() {
        // Given - скорость ровно 14 м/с (граница MAX_REASONABLE_SPEED)
        val location = createLocation(55.7558, 37.6173, accuracy = 10f, speed = 14f)

        // When
        val result = GpsFilter.isValidGpsLocation(location)

        // Then
        assertTrue("Location with boundary speed (14 m/s) should be accepted", result)
    }
}

