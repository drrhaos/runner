package com.runner.academy.util

import com.runner.academy.data.PauseInterval
import com.runner.academy.data.PauseKind
import com.runner.academy.data.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CadenceSeriesTest {

    private val start = 1_000_000L
    private fun at(sec: Int) = start + sec * 1_000L

    /** Points every 10 s, 30 m apart (no GPS gap). */
    private fun point(sec: Int, cadence: Float?, afterGap: Boolean = false) =
        TrackPoint(55.75, 37.60 + sec * 0.000045, at(sec), 5f, 3f, null, afterGap = afterGap, cadence = cadence)

    /** The runs of the line as seconds from the start. */
    private fun runs(points: List<TrackPoint>, pauses: List<PauseInterval>? = null) =
        TrackChartBuilder.buildCadenceSeries(points, pauses).map { run -> run.map { (it.timeMinutes * 60f).roundToInt() } }

    @Test
    fun `a track with cadence is one line in minutes from the start`() {
        val points = (0..60 step 10).map { point(it, 170f + it) }

        val series = TrackChartBuilder.buildCadenceSeries(points, null)

        assertEquals(listOf((0..60 step 10).toList()), runs(points))
        assertEquals(1f, series.single()[6].timeMinutes, 0.001f)
        assertEquals(230f, series.single()[6].cadence, 0.001f)
    }

    @Test
    fun `no cadence in the points gives no series`() {
        assertTrue(TrackChartBuilder.buildCadenceSeries((0..60 step 10).map { point(it, null) }, null).isEmpty())
        assertTrue(TrackChartBuilder.buildCadenceSeries(emptyList(), null).isEmpty())
    }

    @Test
    fun `sensor silence breaks the line instead of drawing zero`() {
        val points = listOf(point(0, 170f), point(10, 172f), point(20, null), point(30, 168f), point(40, 170f))

        assertEquals(listOf(listOf(0, 10), listOf(30, 40)), runs(points))
    }

    @Test
    fun `points inside an auto-pause are left out and the line breaks`() {
        // Standing 20..50 s on an auto-pause: the fixes go on with cadence 0
        val points = listOf(0, 10, 20, 30, 40, 50, 60).map { point(it, if (it in 21..49) 0f else 170f) }
        val pauses = listOf(PauseInterval(at(20), at(50), PauseKind.AUTO))

        assertEquals(listOf(listOf(0, 10, 20), listOf(50, 60)), runs(points, pauses))
    }

    @Test
    fun `a manual pause between two fixes breaks the line`() {
        val points = listOf(point(0, 170f), point(10, 170f), point(70, 170f), point(80, 170f))
        val pauses = listOf(PauseInterval(at(15), at(65), PauseKind.MANUAL))

        assertEquals(listOf(listOf(0, 10), listOf(70, 80)), runs(points, pauses))
    }

    @Test
    fun `standing without an auto-pause stays on the line`() {
        val points = listOf(point(0, 170f), point(10, 0f), point(20, 0f), point(30, 170f))

        assertEquals(listOf(listOf(0, 10, 20, 30)), runs(points))
    }

    @Test
    fun `a GPS gap breaks the line`() {
        val points = listOf(point(0, 170f), point(10, 170f), point(80, 170f, afterGap = true), point(90, 170f))

        assertEquals(listOf(listOf(0, 10), listOf(80, 90)), runs(points))
    }
}
