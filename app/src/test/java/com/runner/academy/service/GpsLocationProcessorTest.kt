package com.runner.academy.service

import android.location.Location
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

    private fun process(
        location: Location,
        previous: GpsLocationProcessor.ProcessResult? = null,
        resumeAfterGap: Boolean = false
    ) = processor.processLocation(
        location,
        previous?.trackPoints ?: mutableListOf(),
        previous?.trackDataPoints ?: mutableListOf(),
        previous?.rawTrackDataPoints ?: mutableListOf(),
        resumeAfterGap = resumeAfterGap
    )

    @Test
    fun countSilence_reports_the_step_distance_once() {
        processor.reset(stepDistance = com.runner.academy.util.StepDistanceEstimator { steps, _ -> steps.toFloat() })
        processor.processLocation(
            location(55.7558, 37.6173, time = 1_000_000L), mutableListOf(), mutableListOf(), mutableListOf(),
            steps = 0
        )

        assertEquals(0f, processor.countSilence(1_010_000L, steps = 30, cadence = 170f), 0.01f)
        assertEquals(90f, processor.countSilence(1_030_000L, steps = 90, cadence = 170f), 0.5f)
        // Same steps again: nothing new to show
        assertEquals(0f, processor.countSilence(1_031_000L, steps = 90, cadence = 170f), 0.01f)
        assertEquals(90f, processor.pendingStepMeters, 0.5f)
    }

    @Test
    fun processLocation_afterGap_acceptsAnchorWithZeroSegmentDistance() {
        val first = location(55.7558, 37.6173, time = 1_000_000L)
        // ~45 m after 20 s without fixes; the LOST flag forces the resume
        val afterGap = location(55.7559, 37.6180, time = 1_000_000L + GpsFilter.GAP_RESUME_THRESHOLD_MS)

        val firstResult = process(first) as GpsLocationProcessor.ProcessResult.Accepted
        val gapResult = process(afterGap, firstResult, resumeAfterGap = true)

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

        val firstResult = process(first)
        val secondResult = process(second, firstResult) as GpsLocationProcessor.ProcessResult.Accepted

        assertFalse(secondResult.afterGap)
        assertTrue(secondResult.segmentDistanceMeters > 2f)
    }

    @Test
    fun processLocation_afterLongStandWithNearDuplicates_addsDistanceWithoutGap() {
        val first = location(55.7558, 37.6173, time = 1_000_000L, speed = 0f)
        var last = process(first)
        // Standing for a minute: near-duplicates keep the gap clock moving
        for (second in 1..59) {
            val jitter = if (second % 2 == 0) 0.000001 else -0.000001
            last = process(location(55.7558 + jitter, 37.6173, time = 1_000_000L + second * 1_000L, speed = 0f), last)
            assertTrue(last is GpsLocationProcessor.ProcessResult.Rejected)
            assertTrue((last as GpsLocationProcessor.ProcessResult.Rejected).refreshGapClock)
        }
        // ~11 m north, 60 s after the last accepted fix
        val moving = location(55.7559, 37.6173, time = 1_060_000L)
        val movingResult = process(moving, last) as GpsLocationProcessor.ProcessResult.Accepted

        assertFalse(movingResult.afterGap)
        assertFalse(movingResult.trackDataPoints.last().afterGap)
        assertTrue(movingResult.segmentDistanceMeters > 2f)
    }

    @Test
    fun processLocation_afterSilence_treatsLongStandAsGap() {
        val first = location(55.7558, 37.6173, time = 1_000_000L, speed = 0f)
        val moving = location(55.7559, 37.6173, time = 1_060_000L)

        val firstResult = process(first)
        val movingResult = process(moving, firstResult) as GpsLocationProcessor.ProcessResult.Accepted

        // No fixes for a minute: a GPS gap, no distance across it
        assertTrue(movingResult.afterGap)
        assertEquals(0f, movingResult.segmentDistanceMeters, 0.01f)
    }

    @Test
    fun reset_startsANewRunWithoutAnchor() {
        process(location(55.7558, 37.6173, time = 1_000_000L))
        assertNotNull(processor.anchor)

        processor.reset()

        val next = process(location(55.7600, 37.6173, time = 1_001_000L)) as GpsLocationProcessor.ProcessResult.Accepted
        assertFalse(next.afterGap)
        assertEquals(0f, next.segmentDistanceMeters, 0.01f)
    }
}
