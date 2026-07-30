package com.runner.academy.util

import android.location.Location
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
    fun isvalidgpslocation_should_accept_valid_location() {
        // Given
        val location = createLocation(55.7558, 37.6173, accuracy = 10f, speed = 3f)

        // When
        val result = GpsFilter.isValidGpsLocation(location)

        // Then
        assertTrue("Valid location should be accepted", result)
    }

    @Test
    fun isvalidgpslocation_should_accept_location_without_accuracy_when_coordinates_are_valid() {
        // Given
        val location = Location("test").apply {
            latitude = 55.7558
            longitude = 37.6173
            speed = 3f
        }

        // When
        val result = GpsFilter.isValidGpsLocation(location)

        // Then
        assertTrue("Location without accuracy should be accepted when coordinates are valid", result)
    }

    @Test
    fun isvalidgpslocation_should_accept_location_without_speed_when_coordinates_are_valid() {
        // Given
        val location = Location("test").apply {
            latitude = 55.7558
            longitude = 37.6173
            accuracy = 10f
        }

        // When
        val result = GpsFilter.isValidGpsLocation(location)

        // Then
        assertTrue("Location without speed should be accepted when coordinates are valid", result)
    }

    @Test
    fun isvalidgpslocation_should_reject_location_with_poor_accuracy() {
        // Given
        val location = createLocation(55.7558, 37.6173, accuracy = 200f, speed = 3f)

        // When
        val result = GpsFilter.isValidGpsLocation(location)

        // Then
        assertFalse("Location with accuracy > 150m should be rejected", result)
    }

    @Test
    fun isvalidgpslocation_should_reject_location_with_absurd_reported_speed() {
        // Given — мгновенная скорость с GPS часто врёт; режем только абсурд
        val location = createLocation(55.7558, 37.6173, accuracy = 10f, speed = 55f)

        // When
        val result = GpsFilter.isValidGpsLocation(location)

        // Then
        assertFalse("Location with absurd reported speed should be rejected", result)
    }

    @Test
    fun isvalidgpslocation_should_accept_location_with_elevated_reported_speed() {
        // Given — 15 м/с (~54 км/ч) раньше отбрасывалось, но для спусков/шума GPS это нормально
        val location = createLocation(55.7558, 37.6173, accuracy = 10f, speed = 15f)

        // When
        val result = GpsFilter.isValidGpsLocation(location)

        // Then
        assertTrue("Elevated reported speed should be accepted", result)
    }

    @Test
    fun isvalidgpslocation_should_reject_invalid_latitude() {
        // Given - широта больше 90
        val location = createLocation(91.0, 37.6173, accuracy = 10f, speed = 3f)

        // When
        val result = GpsFilter.isValidGpsLocation(location)

        // Then
        assertFalse("Location with latitude > 90 should be rejected", result)
    }

    @Test
    fun isvalidgpslocation_should_reject_invalid_longitude() {
        // Given - долгота больше 180
        val location = createLocation(55.7558, 181.0, accuracy = 10f, speed = 3f)

        // When
        val result = GpsFilter.isValidGpsLocation(location)

        // Then
        assertFalse("Location with longitude > 180 should be rejected", result)
    }

    @Test
    fun isvalidgpslocation_should_reject_nan_coordinates() {
        // Given
        val location = createLocation(Double.NaN, 37.6173, accuracy = 10f, speed = 3f)

        // When
        val result = GpsFilter.isValidGpsLocation(location)

        // Then
        assertFalse("Location with NaN latitude should be rejected", result)
    }

    @Test
    fun filtergpsoutlier_should_accept_first_location() {
        // Given
        val location = createLocation(55.7558, 37.6173, accuracy = 10f, speed = 3f)

        // When
        val result = GpsFilter.filterGpsOutlier(location, null)

        // Then
        assertNotNull("First valid location should be accepted", result)
        assertEquals(location, result)
    }

    @Test
    fun filtergpsoutlier_should_reject_first_location_if_invalid() {
        // Given
        val location = createLocation(55.7558, 37.6173, accuracy = 200f, speed = 3f)

        // When
        val result = GpsFilter.filterGpsOutlier(location, null)

        // Then
        assertNull("Invalid first location should be rejected", result)
    }

    @Test
    fun filtergpsoutlier_should_accept_location_close_to_previous() {
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
    fun filtergpsoutlier_should_reject_outlier_too_far_from_previous() {
        // Given — телепорт ~900м за 2с (выше MAX_DISTANCE 800м)
        val previousLocation = createLocation(55.7558, 37.6173, accuracy = 10f, speed = 3f)
        val newLocation = createLocation(55.7640, 37.6250, accuracy = 10f, speed = 3f,
            time = previousLocation.time + 2000)

        // When
        val result = GpsFilter.filterGpsOutlier(newLocation, previousLocation)

        // Then
        assertNull("Location too far from previous should be rejected", result)
    }

    @Test
    fun filtergpsoutlier_should_reject_location_with_earlier_timestamp() {
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
    fun filtergpsoutlier_should_accept_high_accuracy_location_even_if_far() {
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
    fun createvalidgeopoint_should_create_geopoint_from_valid_location() {
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
    fun createvalidgeopointwithfiltering_should_create_geopoint_from_filtered_location() {
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
    fun createvalidgeopointwithfiltering_should_return_null_for_outlier() {
        // Given — выброс (слишком далеко за короткое время)
        val previousLocation = createLocation(55.7558, 37.6173, accuracy = 10f, speed = 3f)
        val newLocation = createLocation(55.7640, 37.6250, accuracy = 10f, speed = 3f,
            time = previousLocation.time + 2000)

        // When
        val result = GpsFilter.createValidGeoPointWithFiltering(newLocation, previousLocation)

        // Then
        assertNull("Outlier should result in null GeoPoint", result)
    }

    @Test
    fun filtergpsoutlier_should_handle_edge_case_with_zero_distance() {
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
    fun filtergpsoutlier_should_handle_location_at_boundary_distances() {
        // Given — ~450 м за 35 с при разумной скорости
        val previousLocation = createLocation(55.7558, 37.6173, accuracy = 10f, speed = 3f)
        val newLocation = createLocation(55.7598, 37.6173, accuracy = 10f, speed = 3f,
            time = previousLocation.time + 35_000)

        // When
        val result = GpsFilter.filterGpsOutlier(newLocation, previousLocation)

        // Then
        assertNotNull("Location at boundary distance should be accepted", result)
    }

    @Test
    fun isvalidgpslocation_should_accept_location_with_boundary_accuracy() {
        // Given — точность ровно 150м (граница)
        val location = createLocation(55.7558, 37.6173, accuracy = 150f, speed = 3f)

        // When
        val result = GpsFilter.isValidGpsLocation(location)

        // Then
        assertTrue("Location with boundary accuracy (150m) should be accepted", result)
    }

    @Test
    fun filtergpsoutlier_should_accept_when_previous_speed_is_zero() {
        // Given — раньше expectedMaxDistance ≈ 0 при speed=0 и отбрасывал нормальное движение
        val previousLocation = createLocation(55.7558, 37.6173, accuracy = 25f, speed = 0f, time = 1_000_000L)
        val newLocation = createLocation(55.7560, 37.6173, accuracy = 25f, speed = 2f,
            time = previousLocation.time + 2_000L)

        val result = GpsFilter.filterGpsOutlier(newLocation, previousLocation)

        assertNotNull("Movement after zero-speed fix should be accepted", result)
    }

    @Test
    fun filtergpsoutlier_should_accept_far_point_after_gap_threshold() {
        val previousLocation = createLocation(55.7558, 37.6173, accuracy = 10f, speed = 3f, time = 1_000_000L)
        val newLocation = createLocation(55.7640, 37.6200, accuracy = 10f, speed = 3f,
            time = previousLocation.time + GpsFilter.GAP_RESUME_THRESHOLD_MS)

        val result = GpsFilter.filterGpsOutlier(newLocation, previousLocation)

        assertNotNull("Point after GPS gap should be accepted as resume anchor", result)
        assertTrue(GpsFilter.isGapResume(previousLocation, newLocation))
    }

    @Test
    fun filtergpsoutlier_should_accept_far_point_when_force_gap_resume() {
        val previousLocation = createLocation(55.7558, 37.6173, accuracy = 10f, speed = 3f, time = 1_000_000L)
        val newLocation = createLocation(55.7640, 37.6200, accuracy = 10f, speed = 3f,
            time = previousLocation.time + 2_000L)

        val result = GpsFilter.filterGpsOutlier(
            newLocation,
            previousLocation,
            forceGapResume = true
        )

        assertNotNull("Forced gap resume should accept otherwise-outlier point", result)
        assertTrue(GpsFilter.isGapResume(previousLocation, newLocation, forceGapResume = true))
    }

    @Test
    fun filtergpsoutlier_should_still_reject_invalid_point_on_gap_resume() {
        val previousLocation = createLocation(55.7558, 37.6173, accuracy = 10f, speed = 3f, time = 1_000_000L)
        val newLocation = createLocation(55.7640, 37.6200, accuracy = 200f, speed = 3f,
            time = previousLocation.time + GpsFilter.GAP_RESUME_THRESHOLD_MS)

        val result = GpsFilter.filterGpsOutlier(newLocation, previousLocation, forceGapResume = true)

        assertNull("Invalid accuracy should still be rejected on gap resume", result)
    }
}

