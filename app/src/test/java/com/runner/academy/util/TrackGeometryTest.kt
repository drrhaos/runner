package com.runner.academy.util

import com.runner.academy.data.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TrackGeometryTest {

    // ~111 m per 0.001° of latitude
    private fun point(lat: Double, afterGap: Boolean = false, bridgeMeters: Float? = null) = TrackPoint(
        latitude = lat,
        longitude = 37.0,
        timestamp = 0L,
        accuracy = 5f,
        speed = 3f,
        altitude = null,
        afterGap = afterGap,
        bridgeMeters = bridgeMeters
    )

    @Test
    fun plain_step_counts_its_straight_distance() {
        val total = TrackGeometry.totalDistanceMeters(listOf(point(55.0), point(55.001)))
        assertEquals(111f, total, 1f)
    }

    @Test
    fun gap_counts_nothing() {
        val total = TrackGeometry.totalDistanceMeters(listOf(point(55.0), point(55.001, afterGap = true)))
        assertEquals(0f, total, 0.01f)
    }

    @Test
    fun bridge_counts_its_stored_distance_not_the_straight_line() {
        val points = listOf(point(55.0), point(55.001, afterGap = true, bridgeMeters = 300f), point(55.002))
        assertEquals(300f + 111f, TrackGeometry.totalDistanceMeters(points), 1f)
    }

    @Test
    fun tail_after_the_last_point_counts_toward_the_total() {
        val points = listOf(point(55.0), point(55.001).copy(tailMeters = 250f))
        assertEquals(111f + 250f, TrackGeometry.totalDistanceMeters(points), 1f)
        assertEquals(250f, TrackGeometry.tailMeters(points), 0.01f)
        assertEquals(0f, TrackGeometry.tailMeters(listOf(point(55.0))), 0.01f)
    }

    @Test
    fun bridge_is_a_break_in_the_line_but_not_a_lost_distance() {
        val prev = point(55.0)
        val bridged = point(55.001, afterGap = true, bridgeMeters = 120f)
        assertTrue(TrackGeometry.isTrackGapStep(prev, bridged))
        assertTrue(TrackGeometry.isBridgeStep(bridged))
        assertFalse(TrackGeometry.isBridgeStep(point(55.001, afterGap = true)))
        assertFalse(TrackGeometry.isBridgeStep(point(55.001)))
    }

    @Test
    fun lead_in_on_the_first_point_counts_once() {
        val track = listOf(point(55.0, bridgeMeters = 120f), point(55.001))
        assertEquals(120f, TrackGeometry.leadInMeters(track), 0.01f)
        assertEquals(231f, TrackGeometry.totalDistanceMeters(track), 1f)
        assertEquals(120f, TrackGeometry.totalDistanceMeters(track.take(1)), 0.01f)
    }

    @Test
    fun tracks_without_a_lead_in_are_unchanged() {
        assertEquals(0f, TrackGeometry.leadInMeters(listOf(point(55.0), point(55.001))), 0f)
        assertEquals(0f, TrackGeometry.leadInMeters(emptyList()), 0f)
        // A bridge value on a later point is a bridge, not a lead-in
        assertEquals(0f, TrackGeometry.leadInMeters(listOf(point(55.0), point(55.01, afterGap = true, bridgeMeters = 9f))), 0f)
    }
}
