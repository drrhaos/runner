package com.runner.academy.ui.workout

import com.runner.academy.util.SegmentStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SegmentSummaryTest {

    private fun segment(paceMin: Float, distanceKm: Float = 1f, durationMs: Long = (paceMin * distanceKm * 60_000).toLong()) =
        SegmentStats(
            paceMinPerUnit = paceMin,
            speedDisplay = if (paceMin > 0f) 60f / paceMin else 0f,
            distanceKm = distanceKm,
            durationMs = durationMs,
            startIndex = 0,
            endIndex = 0
        )

    @Test
    fun `fastest and slowest are the segments of the lowest and highest pace`() {
        val summary = SegmentSummary.of(listOf(segment(5.5f), segment(4.75f), segment(6f), segment(5f)), metric = true)!!

        assertEquals(1, summary.fastestIndex)
        assertEquals(2, summary.slowestIndex)
    }

    @Test
    fun `the average pace is the whole time over the whole distance, not the mean of paces`() {
        // 1 km at 4:00 and 3 km at 6:00: 22 minutes over 4 km
        val summary = SegmentSummary.of(listOf(segment(4f), segment(6f, distanceKm = 3f)), metric = true)!!

        assertEquals(5.5f, summary.averagePace, 0.001f)
    }

    @Test
    fun `the average pace is per mile in imperial units`() {
        // 1.609344 km in 8 minutes is 8 minutes per mile
        val summary = SegmentSummary.of(listOf(segment(8f, distanceKm = 1.609344f, durationMs = 480_000L)), metric = false)!!

        assertEquals(8f, summary.averagePace, 0.001f)
    }

    @Test
    fun `segments without a pace are skipped but keep the numbering of the bars`() {
        val summary = SegmentSummary.of(listOf(segment(0f), segment(5f), segment(Float.NaN), segment(6f)), metric = true)!!

        assertEquals(1, summary.fastestIndex)
        assertEquals(3, summary.slowestIndex)
    }

    @Test
    fun `no segment with a pace has no summary`() {
        assertNull(SegmentSummary.of(emptyList(), metric = true))
        assertNull(SegmentSummary.of(listOf(segment(0f)), metric = true))
    }
}
