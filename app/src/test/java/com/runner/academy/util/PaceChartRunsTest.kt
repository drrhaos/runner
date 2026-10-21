package com.runner.academy.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PaceChartRunsTest {

    private fun point(minute: Int, pace: Float) = PaceSpeedPoint(minute.toFloat(), pace, if (pace > 0f) 60f / pace else 0f, minute)

    private fun runs(vararg paces: Float, metric: Boolean = true) =
        TrackChartBuilder.paceRuns(paces.mapIndexed { i, pace -> point(i, pace) }, metric)
            .map { run -> run.map { it.timeMinutes.toInt() } }

    @Test
    fun `a steady run is one line`() {
        assertEquals(listOf(listOf(0, 1, 2)), runs(5f, 5.5f, 6f))
    }

    @Test
    fun `a stop breaks the line instead of diving to zero`() {
        assertEquals(listOf(listOf(0, 1), listOf(3)), runs(5f, 5f, 0f, 5f))
    }

    @Test
    fun `a crawl slower than the limit breaks the line instead of stretching the axis`() {
        assertEquals(listOf(listOf(0), listOf(2)), runs(5f, 45f, 5f))
    }

    @Test
    fun `the limit is per mile without metric`() {
        // 25 min/mi is about 15.5 min/km: still drawn; 40 min/mi is not
        assertTrue(TrackChartBuilder.isChartPace(25f, metric = false))
        assertFalse(TrackChartBuilder.isChartPace(25f, metric = true))
        assertFalse(TrackChartBuilder.isChartPace(40f, metric = false))
    }

    @Test
    fun `no drawable pace is no runs`() {
        assertEquals(emptyList<List<Int>>(), runs(0f, Float.NaN, 0f))
    }
}
