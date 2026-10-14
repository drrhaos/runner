package com.runner.academy.util

import com.runner.academy.data.PauseInterval
import com.runner.academy.data.PauseKind
import com.runner.academy.data.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

class HeartRateSeriesTest {

    private val start = 1_000_000L
    private fun at(sec: Int) = start + sec * 1_000L

    private fun point(sec: Int, hr: Int?) = TrackPoint(55.75, 37.60 + sec * 0.00002, at(sec), 5f, 3f, null, heartRate = hr)

    /** The runs of the line as seconds from the start. */
    private fun runs(points: List<TrackPoint>, pauses: List<PauseInterval>? = null) =
        TrackChartBuilder.buildHeartRateSeries(points, pauses).map { run -> run.map { (it.timeMinutes * 60f).roundToInt() } }

    @Test
    fun `a track with heart rate is one line in minutes from the start`() {
        val points = (0..60 step 5).map { point(it, 140 + it) }

        val series = TrackChartBuilder.buildHeartRateSeries(points, null)

        assertEquals(listOf((0..60 step 5).toList()), runs(points))
        assertEquals(1f, series.single().last().timeMinutes, 0.001f)
        assertEquals(200f, series.single().last().bpm, 0.001f)
    }

    @Test
    fun `no heart rate in the points gives no series`() {
        assertTrue(TrackChartBuilder.buildHeartRateSeries((0..60 step 5).map { point(it, null) }, null).isEmpty())
        assertTrue(TrackChartBuilder.buildHeartRateSeries(emptyList(), null).isEmpty())
    }

    @Test
    fun `a point without a value breaks the line, nothing is interpolated`() {
        val points = listOf(point(0, 150), point(5, 152), point(10, null), point(15, 151), point(20, 150))

        assertEquals(listOf(listOf(0, 5), listOf(15, 20)), runs(points))
    }

    @Test
    fun `a step longer than 10 s breaks the line`() {
        val points = listOf(point(0, 150), point(10, 150), point(21, 150), point(31, 150))

        assertEquals(listOf(listOf(0, 10), listOf(21, 31)), runs(points))
    }

    @Test
    fun `points inside an auto-pause are left out and the line breaks`() {
        val points = (0..60 step 5).map { point(it, if (it in 21..49) 100 else 150) }
        val pauses = listOf(PauseInterval(at(20), at(50), PauseKind.AUTO))

        assertEquals(listOf(listOf(0, 5, 10, 15, 20), listOf(50, 55, 60)), runs(points, pauses))
    }

    @Test
    fun `a manual pause between two close fixes breaks the line`() {
        val points = listOf(point(0, 150), point(5, 150), point(10, 150), point(15, 150))
        val pauses = listOf(PauseInterval(at(6), at(9), PauseKind.MANUAL))

        assertEquals(listOf(listOf(0, 5), listOf(10, 15)), runs(points, pauses))
    }

    // --- Zone colours (HRmax 190: Z3 133, Z4 152, Z5 171) ---

    private fun run(vararg bpm: Int) = bpm.mapIndexed { i, v -> HeartRatePoint(i / 60f, v.toFloat()) }

    @Test
    fun `each segment takes the zone of the mean of its two points`() {
        val zones = HrZones(190)

        // 150–150: Z3; 150–154 (152): Z4; 154–190 (172): Z5; 190–90 (140): Z3
        assertEquals(
            listOf(3, 4, 5, 3),
            TrackChartBuilder.zoneOfSegments(run(150, 150, 154, 190, 90), zones).toList()
        )
    }

    @Test
    fun `a mean on a half is floored like the time in zones`() {
        // 151 and 152: 151.5 is still Z3
        assertEquals(listOf(3), TrackChartBuilder.zoneOfSegments(run(151, 152), HrZones(190)).toList())
    }

    @Test
    fun `a lone point has no segments`() {
        assertEquals(0, TrackChartBuilder.zoneOfSegments(run(150), HrZones(190)).size)
    }
}
