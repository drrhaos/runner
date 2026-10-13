package com.runner.academy.ui.workout

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.data.LineDataSet
import com.runner.academy.R
import com.runner.academy.data.TrackData
import com.runner.academy.data.TrackPoint
import com.runner.academy.util.HrZones
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class HeartRateChartZonesTest {

    private lateinit var context: Context
    private lateinit var renderer: ChartRenderer
    private lateinit var chart: LineChart
    private lateinit var card: View

    @Before
    fun setUp() {
        context = ContextThemeWrapper(ApplicationProvider.getApplicationContext<Context>(), R.style.Theme_Runner)
        chart = LineChart(context)
        card = FrameLayout(context)
        renderer = ChartRenderer(
            paceSpeedHeartChart = LineChart(context),
            elevation = ElevationChartViews(FrameLayout(context), LineChart(context), TextView(context), TextView(context), TextView(context)),
            segmentsChart = BarChart(context),
            textViewPaceSpeedValues = TextView(context),
            cadence = CadenceChartViews(FrameLayout(context), LineChart(context), TextView(context), TextView(context)),
            heartRate = HeartRateChartViews(card, chart, TextView(context), TextView(context)),
            context = context,
            userPreferences = null,
            onPositionSelected = {},
            onSegmentSelected = { _, _ -> },
            onNothingSelected = {}
        )
    }

    private val start = 1_000_000L

    /** One fix every 2 s: 140 (Z3 of HRmax 190) for a minute, then 160 (Z4) for a minute. */
    private fun track() = TrackData(
        points = (0..120 step 2).map { sec ->
            TrackPoint(55.75, 37.60 + sec * 0.00003, start + sec * 1_000L, 5f, 3f, null, heartRate = if (sec < 60) 140 else 160)
        },
        totalDistance = 400f,
        totalDuration = 120_000L,
        avgSpeed = 3.3f,
        maxSpeed = 3.5f,
        startTime = start,
        endTime = start + 120_000L
    )

    private fun color(id: Int) = ContextCompat.getColor(context, id)

    @Test
    fun `without zones the line is one colour and has no bounds`() {
        renderer.updateAllCharts(track())

        val set = chart.data.getDataSetByIndex(0) as LineDataSet
        assertEquals(View.VISIBLE, card.visibility)
        assertEquals(listOf(color(R.color.chart_series_heart_rate)), set.colors)
        assertEquals(0, chart.axisLeft.limitLines.size)
    }

    @Test
    fun `with zones each segment takes its zone colour and the bounds inside the line are drawn`() {
        renderer.hrZones = HrZones(190)
        renderer.heartRateDisplay = HeartRateSummaryDisplay.Value(150, 160)

        renderer.updateAllCharts(track())

        val set = chart.data.getDataSetByIndex(0) as LineDataSet
        assertEquals(set.entryCount, set.colors.size)
        assertEquals(color(R.color.hr_zone_3), set.colors.first())
        assertEquals(color(R.color.hr_zone_4), set.colors.last())
        // 140..160: only the Z4 bound (152) is inside; then the average
        val lines = chart.axisLeft.limitLines
        assertEquals(listOf("Z4", context.getString(R.string.chart_heart_rate_limit_label, 150)), lines.map { it.label })
        assertEquals(color(R.color.chart_axis_text), lines.last().lineColor)
        assertEquals(
            context.getString(R.string.chart_heart_rate_zones_a11y, 150, 140, 160),
            chart.contentDescription.toString()
        )
    }
}
