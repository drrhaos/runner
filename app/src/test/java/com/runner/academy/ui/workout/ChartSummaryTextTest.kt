package com.runner.academy.ui.workout

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.runner.academy.data.SegmentKind
import com.runner.academy.data.WorkoutTemplateSegment
import com.runner.academy.util.SegmentStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ChartSummaryTextTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun segment(paceMin: Float, speed: Float = 60f / paceMin) =
        SegmentStats(paceMin, speed, 1f, (paceMin * 60_000).toLong(), 0, 0)

    private fun interval(kind: SegmentKind, title: String, order: Int) =
        WorkoutTemplateSegment(templateId = 1L, sortOrder = order, kind = kind, title = title)

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
    fun `an interval plan names the bars by their titles and rates only the work`() {
        val plan = listOf(
            interval(SegmentKind.WARMUP, "Easy start", 0),
            interval(SegmentKind.WORK, "Fast 400", 1),
            interval(SegmentKind.RECOVERY, "Jog", 2),
            interval(SegmentKind.CUSTOM, "Strides", 3),
            interval(SegmentKind.COOLDOWN, "Walk", 4)
        )
        // The warm-up is the fastest bar and the cool-down the slowest, yet neither is rated
        val segments = listOf(segment(4f), segment(4.5f), segment(7f), segment(5f), segment(9f))
        assertEquals(
            "Chart by segment: 5 segments, fastest — Fast 400, segment 2, 4 minutes 30 seconds per kilometer, " +
                "slowest — Strides, segment 4, 5 minutes per kilometer",
            ChartSummaryText.segmentsA11y(context, segments, metric = true, speed = false, plan = plan)
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
        assertEquals(
            "интервалы",
            "График по отрезкам: 2 отрезка, быстрее всего — Быстро, отрезок 1, 5 минут на километр, " +
                "медленнее всего — Темп, отрезок 2, 6 минут на километр",
            ChartSummaryText.segmentsA11y(
                context,
                listOf(segment(5f), segment(6f)),
                metric = true,
                speed = false,
                plan = listOf(interval(SegmentKind.WORK, "Быстро", 0), interval(SegmentKind.WORK, "Темп", 1))
            )
        )
    }
}
