package com.runner.academy.service

import android.location.Location
import com.runner.academy.data.WorkoutType
import com.runner.academy.util.GpsFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class GpsLocationProcessorTest {

    private val processor = GpsLocationProcessor()

    private fun location(
        lat: Double,
        lon: Double,
        time: Long,
        accuracy: Float = 10f,
        speed: Float = 3f
    ): Location = Location("test").apply {
        latitude = lat
        longitude = lon
        this.time = time
        this.accuracy = accuracy
        this.speed = speed
    }

    @Test
    fun processLocation_afterGap_acceptsAnchorWithZeroSegmentDistance() {
        val first = location(55.7558, 37.6173, time = 1_000_000L)
        val afterGap = location(55.7640, 37.6200, time = 1_000_000L + GpsFilter.GAP_RESUME_THRESHOLD_MS)

        val firstResult = processor.processLocation(
            first,
            null,
            WorkoutType.EASY_RUN,
            mutableListOf(),
            mutableListOf(),
            mutableListOf()
        ) as GpsLocationProcessor.ProcessResult.Accepted

        val gapResult = processor.processLocation(
            afterGap,
            firstResult.filteredLocation,
            WorkoutType.EASY_RUN,
            firstResult.trackPoints,
            firstResult.trackDataPoints,
            firstResult.rawTrackDataPoints,
            resumeAfterGap = true
        )

        assertNotNull(gapResult)
        assertTrue(gapResult is GpsLocationProcessor.ProcessResult.Accepted)
        val accepted = gapResult as GpsLocationProcessor.ProcessResult.Accepted
        assertEquals(0f, accepted.segmentDistanceMeters, 0.01f)
        assertTrue(accepted.afterGap)
        assertTrue(accepted.trackDataPoints.last().afterGap)
        assertEquals(2, accepted.trackDataPoints.size)
    }

    @Test
    fun processLocation_normalSegment_addsDistance() {
        val first = location(55.7558, 37.6173, time = 1_000_000L)
        // ~11m north in 2s — should be accepted
        val second = location(55.7559, 37.6173, time = 1_002_000L)

        val firstResult = processor.processLocation(
            first,
            null,
            WorkoutType.EASY_RUN,
            mutableListOf(),
            mutableListOf(),
            mutableListOf()
        ) as GpsLocationProcessor.ProcessResult.Accepted

        val secondResult = processor.processLocation(
            second,
            firstResult.filteredLocation,
            WorkoutType.EASY_RUN,
            firstResult.trackPoints,
            firstResult.trackDataPoints,
            firstResult.rawTrackDataPoints
        ) as GpsLocationProcessor.ProcessResult.Accepted

        assertFalse(secondResult.afterGap)
        assertTrue(secondResult.segmentDistanceMeters > 2f)
    }
}
