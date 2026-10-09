package com.runner.academy.ui.workout

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.runner.academy.util.SegmentStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DetailA11yTextTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun segment(paceMin: Float, speed: Float = 60f / paceMin) =
        SegmentStats(paceMin, speed, 1f, (paceMin * 60_000).toLong(), 0, 0)

    // Tiles

    @Test
    fun `the distance is read in whole kilometres and metres as shown`() {
        assertEquals("Distance 5 kilometers 20 meters", DetailTileText.distanceA11y(context, 5.02f))
        assertEquals("rounded like the two decimals of the tile", "Distance 5 kilometers 10 meters", DetailTileText.distanceA11y(context, 5.0099f))
        assertEquals("Distance 5 kilometers", DetailTileText.distanceA11y(context, 5.0f))
        assertEquals("Distance 350 meters", DetailTileText.distanceA11y(context, 0.35f))
        assertEquals("Distance 0 meters", DetailTileText.distanceA11y(context, 0f))
    }

    @Test
    @Config(qualifiers = "ru")
    fun `the distance in russian`() {
        assertEquals("Дистанция 21 километр 100 метров", DetailTileText.distanceA11y(context, 21.1f))
        assertEquals("Дистанция 3 километра", DetailTileText.distanceA11y(context, 3f))
    }

    @Test
    fun `the time next to the pace is the moving time when the elapsed one has its own tile`() {
        assertEquals("Moving time 25 minutes 30 seconds", DetailTileText.durationA11y(context, 1_530_000L, showsElapsed = true))
        assertEquals("Time 25 minutes 30 seconds", DetailTileText.durationA11y(context, 1_530_000L, showsElapsed = false))
        assertEquals("Elapsed time 1 hour 2 minutes", DetailTileText.elapsedA11y(context, 3_720_000L))
    }

    @Test
    @Config(qualifiers = "ru")
    fun `the time in russian`() {
        assertEquals("Время в движении 25 минут 30 секунд", DetailTileText.durationA11y(context, 1_530_000L, showsElapsed = true))
        assertEquals("Время 21 минута", DetailTileText.durationA11y(context, 1_260_000L, showsElapsed = false))
        assertEquals("Общее время 28 минут 40 секунд", DetailTileText.elapsedA11y(context, 1_720_000L))
    }

    @Test
    fun `the pace is read in minutes and seconds per kilometre, an unknown one says so`() {
        assertEquals("Average pace 5 minutes 30 seconds per kilometer", DetailTileText.paceA11y(context, 5.5f))
        assertEquals("Average pace 6 minutes per kilometer", DetailTileText.paceA11y(context, 6f))
        assertEquals("Average pace unknown", DetailTileText.paceA11y(context, 0f))
    }

    @Test
    @Config(qualifiers = "ru")
    fun `the pace in russian`() {
        assertEquals("Средний темп 5 минут 30 секунд на километр", DetailTileText.paceA11y(context, 5.5f))
        assertEquals("Средний темп неизвестен", DetailTileText.paceA11y(context, 0f))
    }

    @Test
    fun `the speed is read with the decimal of the tile, a whole one without it`() {
        assertEquals("Average speed 11.8 kilometers per hour", DetailTileText.speedA11y(context, 11.8f))
        assertEquals("Average speed 12 kilometers per hour", DetailTileText.speedA11y(context, 11.97f))
        assertEquals("Average speed 1 kilometer per hour", DetailTileText.speedA11y(context, 1f))
    }

    @Test
    @Config(qualifiers = "ru")
    fun `the speed in russian`() {
        assertEquals("Средняя скорость 11,8 километра в час", DetailTileText.speedA11y(context, 11.8f))
        assertEquals("Средняя скорость 21 километр в час", DetailTileText.speedA11y(context, 21f))
        assertEquals("Средняя скорость 12 километров в час", DetailTileText.speedA11y(context, 12f))
    }

    @Test
    fun `the calories are declined`() {
        assertEquals("Calories 300 kilocalories", DetailTileText.caloriesA11y(context, 300))
        assertEquals("Calories 1 kilocalorie", DetailTileText.caloriesA11y(context, 1))
    }

    @Test
    @Config(qualifiers = "ru")
    fun `the calories in russian`() {
        assertEquals("Калории 300 килокалорий", DetailTileText.caloriesA11y(context, 300))
        assertEquals("Калории 22 килокалории", DetailTileText.caloriesA11y(context, 22))
        assertEquals("Калории 1 килокалория", DetailTileText.caloriesA11y(context, 1))
    }

    // Charts

    @Test
    fun `the pace chart reads the average, the best and the worst split`() {
        assertEquals(
            "Pace chart: average 5 minutes 30 seconds per kilometer, best 5 minutes per kilometer, " +
                "worst 6 minutes per kilometer",
            ChartSummaryText.paceA11y(context, listOf(segment(5f), segment(6f)), metric = true)
        )
    }

    @Test
    fun `the pace chart in imperial units reads per mile`() {
        val mile = SegmentStats(8f, 7.5f, 1.60934f, 480_000L, 0, 0)
        assertEquals(
            "Pace chart: average 8 minutes per mile, best 8 minutes per mile, worst 8 minutes per mile",
            ChartSummaryText.paceA11y(context, listOf(mile), metric = false)
        )
    }

    @Test
    fun `a chart without a pace has no summary`() {
        assertNull(ChartSummaryText.paceA11y(context, emptyList(), metric = true))
        assertNull(ChartSummaryText.segmentsA11y(context, listOf(segment(0f, 0f)), metric = true, speed = false))
    }

    @Test
    fun `the segment chart names the fastest and the slowest bar in the shown unit`() {
        val segments = listOf(segment(5.5f), segment(5f), segment(6f, 10f))
        assertEquals(
            "Chart by segment: 3 segments, fastest — segment 2, 5 minutes per kilometer, " +
                "slowest — segment 3, 6 minutes per kilometer",
            ChartSummaryText.segmentsA11y(context, segments, metric = true, speed = false)
        )
        assertEquals(
            "Chart by segment: 3 segments, fastest — segment 2, 12 kilometers per hour, " +
                "slowest — segment 3, 10 kilometers per hour",
            ChartSummaryText.segmentsA11y(context, segments, metric = true, speed = true)
        )
        assertEquals(
            "Chart by segment: 3 segments, fastest — segment 2, 12 miles per hour, " +
                "slowest — segment 3, 10 miles per hour",
            ChartSummaryText.segmentsA11y(context, segments, metric = false, speed = true)
        )
    }

    @Test
    fun `one segment is read once`() {
        assertEquals(
            "Chart by segment: one segment, 5 minutes per kilometer",
            ChartSummaryText.segmentsA11y(context, listOf(segment(5f)), metric = true, speed = false)
        )
    }

    @Test
    @Config(qualifiers = "ru")
    fun `the chart summaries in russian`() {
        assertEquals(
            "График темпа: средний 5 минут 30 секунд на километр, лучший 5 минут на километр, " +
                "худший 6 минут на километр",
            ChartSummaryText.paceA11y(context, listOf(segment(5f), segment(6f)), metric = true)
        )
        assertEquals(
            "График по отрезкам: 2 отрезка, быстрее всего — отрезок 1, 12 километров в час, " +
                "медленнее всего — отрезок 2, 10,5 километра в час",
            ChartSummaryText.segmentsA11y(context, listOf(segment(5f), segment(6f, 10.5f)), metric = true, speed = true)
        )
        assertEquals(
            "в милях",
            "График по отрезкам: 2 отрезка, быстрее всего — отрезок 1, 5 минут на милю, " +
                "медленнее всего — отрезок 2, 6 минут на милю",
            ChartSummaryText.segmentsA11y(context, listOf(segment(5f), segment(6f)), metric = false, speed = false)
        )
    }
}
