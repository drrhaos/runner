package com.runner.academy.ui.workout

import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import com.runner.academy.R
import com.runner.academy.data.ElevationSource
import com.runner.academy.data.TrackData
import com.runner.academy.data.TrackPoint
import com.runner.academy.data.localizedTitle
import com.runner.academy.util.HeartRatePoint
import com.runner.academy.util.HrZones
import com.runner.academy.util.SegmentStats
import com.runner.academy.util.SpeedPaceCalculator
import com.runner.academy.util.TrackChartBuilder
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.charts.BarLineChartBase
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.LimitLine
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.components.YAxis
import com.github.mikephil.charting.data.*
import com.github.mikephil.charting.formatter.ValueFormatter
import com.github.mikephil.charting.highlight.Highlight
import com.github.mikephil.charting.listener.OnChartValueSelectedListener
import kotlin.math.roundToInt

/** The cadence card: hidden as a whole, its chart, the tap values and the average in the title. */
class CadenceChartViews(
    val card: android.view.View,
    val chart: LineChart,
    val values: android.widget.TextView,
    val average: android.widget.TextView
)

/** The heart rate card: hidden as a whole, its chart, the tap values and the average and maximum in the title. */
class HeartRateChartViews(
    val card: android.view.View,
    val chart: LineChart,
    val values: android.widget.TextView,
    val summary: android.widget.TextView
)

/**
 * The elevation card: hidden as a whole, its chart, the tap values, gain and loss in the title
 * and the source under it.
 */
class ElevationChartViews(
    val card: android.view.View,
    val chart: LineChart,
    val values: android.widget.TextView,
    val summary: android.widget.TextView,
    val source: android.widget.TextView
)

/**
 * Handles all chart configuration and rendering for workout detail screen.
 * Manages elevation profile, speed/pace charts, and segment bar charts.
 */
class ChartRenderer(
    private val paceSpeedHeartChart: LineChart,
    private val elevation: ElevationChartViews,
    private val segmentsChart: BarChart,
    private val textViewPaceSpeedValues: android.widget.TextView,
    private val cadence: CadenceChartViews,
    private val heartRate: HeartRateChartViews,
    private val context: android.content.Context,
    private val userPreferences: com.runner.academy.util.UserPreferences?,
    private val onPositionSelected: (TrackPoint) -> Unit,
    private val onSegmentSelected: (startPoint: TrackPoint, endPoint: TrackPoint) -> Unit,
    private val onNothingSelected: () -> Unit
) {

    var segmentsDisplayMode: SegmentsDisplayMode = SegmentsDisplayMode.PACE
        set(value) {
            field = value
        }

    /** When set, bar chart splits by template intervals instead of km/mile. */
    var intervalPlanSegments: List<com.runner.academy.data.WorkoutTemplateSegment> = emptyList()

    /** The stored cadence of the workout, as the tile shows it; set on every row update. */
    var cadenceDisplay: CadenceDisplay = CadenceDisplay.None
        set(value) {
            if (field == value) return
            field = value
            applyCadenceDisplay()
        }

    /** Min and max of the drawn cadence line; null: nothing to draw. */
    private var cadenceRange: Pair<Int, Int>? = null

    /** The stored average and maximum heart rate, as the tile shows them; set on every row update. */
    var heartRateDisplay: HeartRateSummaryDisplay = HeartRateSummaryDisplay.None
        set(value) {
            if (field == value) return
            field = value
            applyHeartRateDisplay()
        }

    /** Min and max of the drawn heart rate line; null: under two points to draw. */
    private var heartRateRange: Pair<Int, Int>? = null

    /**
     * The zones by the current HRmax, set before [updateAllCharts]: the heart rate line is
     * coloured by zone, with a dashed line and "Z1"…"Z5" at each bound; null: one colour.
     */
    var hrZones: HrZones? = null

    /** The stored gain and loss of the workout, as the tiles show them; set on every row update. */
    var elevationDisplay: ElevationDisplay = ElevationDisplay.None
        set(value) {
            if (field == value) return
            field = value
            applyElevationDisplay()
        }

    /** Min and max of the drawn elevation line, whole metres; null: nothing to draw. */
    private var elevationRange: Pair<Int, Int>? = null

    enum class SegmentsDisplayMode {
        PACE, SPEED
    }

    fun updateAllCharts(trackData: TrackData) {
        if (trackData.points.isEmpty()) return
        try {
            // Built once for the pace chart summary and the km/mile bars
            val distanceSegments = distanceSegments(trackData)
            updatePaceSpeedHeartChart(trackData, distanceSegments)
            updateCadenceChart(trackData)
            updateHeartRateChart(trackData)
            updateElevationChart(trackData)
            updateSegmentsChart(trackData, distanceSegments)
        } catch (e: Throwable) {
            android.util.Log.e("ChartRenderer", "Failed to update charts", e)
            paceSpeedHeartChart.visibility = android.view.View.GONE
            cadence.card.visibility = android.view.View.GONE
            heartRate.card.visibility = android.view.View.GONE
            elevation.card.visibility = android.view.View.GONE
            segmentsChart.visibility = android.view.View.GONE
        }
    }

    fun updateSegmentsChartOnly(trackData: TrackData) {
        try {
            updateSegmentsChart(trackData, distanceSegments(trackData))
        } catch (e: Throwable) {
            android.util.Log.e("ChartRenderer", "Failed to update segments chart", e)
            segmentsChart.visibility = android.view.View.GONE
        }
    }

    /** The km or mile splits of the track, built on first use. */
    private fun distanceSegments(trackData: TrackData): Lazy<List<SegmentStats>> = lazy {
        SpeedPaceCalculator.buildSegments(trackData.points, userPreferences?.isMetricSystem() ?: true)
    }

    private fun updatePaceSpeedHeartChart(trackData: TrackData, distanceSegments: Lazy<List<SegmentStats>>) {
        val chart = paceSpeedHeartChart
        val points = trackData.points

        if (points.size < 2) {
            chart.visibility = android.view.View.GONE
            return
        }

        chart.visibility = android.view.View.VISIBLE
        configureInteraction(chart, legend = true)

        val textColor = color(R.color.chart_axis_text)
        val gridColor = color(R.color.chart_grid)
        val paceColor = color(R.color.chart_series_pace)
        val speedColor = color(R.color.chart_series_speed)

        val isMetric = userPreferences?.isMetricSystem() ?: true
        val paceSpeedSeries = SpeedPaceCalculator.buildPaceSpeedSeries(points, isMetric)
        if (paceSpeedSeries.isEmpty()) {
            chart.visibility = android.view.View.GONE
            return
        }

        val entriesPace = paceSpeedSeries.map {
            Entry(it.timeMinutes, it.paceMinPerUnit.takeIf { v -> v.isFinite() } ?: 0f)
        }
        val entriesSpeed = paceSpeedSeries.map {
            Entry(it.timeMinutes, it.speedDisplay.takeIf { v -> v.isFinite() } ?: 0f)
        }

        val paceLabel = if (isMetric) {
            context.getString(R.string.chart_pace_label)
        } else {
            context.getString(R.string.chart_pace_label_miles)
        }
        val speedLabel = if (isMetric) {
            context.getString(R.string.chart_speed_label)
        } else {
            context.getString(R.string.chart_speed_label_miles)
        }

        val dataSetPace = LineDataSet(entriesPace, paceLabel).apply {
            color = paceColor
            lineWidth = 2f
            setCircleColor(paceColor)
            setDrawCircles(false)
            setDrawValues(false)
            axisDependency = YAxis.AxisDependency.LEFT
        }

        val dataSetSpeed = LineDataSet(entriesSpeed, speedLabel).apply {
            color = speedColor
            lineWidth = 2f
            setCircleColor(speedColor)
            setDrawCircles(false)
            setDrawValues(false)
            axisDependency = YAxis.AxisDependency.RIGHT
        }

        val lineData = LineData(dataSetPace, dataSetSpeed)
        chart.data = lineData

        configureXAxis(chart, textColor, gridColor) { value ->
            val minutes = value.toInt()
            context.getString(R.string.chart_time_format, minutes)
        }

        // Axis labels are text: the neutral colour, not the series hue (the legend tells them apart)
        configureLeftAxis(chart, textColor, gridColor)

        val rightAxis = chart.axisRight
        rightAxis.isEnabled = true
        rightAxis.setDrawGridLines(false)
        rightAxis.textColor = textColor
        rightAxis.axisLineColor = textColor

        chart.legend.textColor = textColor
        chart.setDrawMarkers(false)
        // The splits, not the per-step line: one GPS jump would be the "best" pace
        chart.contentDescription = ChartSummaryText.paceA11y(context, distanceSegments.value, isMetric)
            ?: context.getString(R.string.workout_details_pace_speed_chart_title)

        selectByTime(chart, points, textViewPaceSpeedValues) { e ->
            // Nearest series entry for the values
            val nearestEntry = paceSpeedSeries.minByOrNull { kotlin.math.abs(it.timeMinutes - e.x) }

            val (paceMinutes, paceSeconds) = nearestEntry?.let {
                SpeedPaceCalculator.paceToMinutesSeconds(it.paceMinPerUnit)
            } ?: (0 to 0)
            val paceStr = if (isMetric) {
                context.getString(R.string.chart_pace_value_km, paceMinutes, paceSeconds)
            } else {
                context.getString(R.string.chart_pace_value_miles, paceMinutes, paceSeconds)
            }
            val speedDisplay = nearestEntry?.speedDisplay ?: 0f
            val speedStr = if (isMetric) {
                context.getString(R.string.chart_speed_value_kmh, speedDisplay)
            } else {
                context.getString(R.string.chart_speed_value_mph, speedDisplay)
            }

            context.getString(R.string.chart_time_format, e.x.toInt()) + "\n" +
                "${context.getString(R.string.workout_details_speed)}: $speedStr\n" +
                "${context.getString(R.string.workout_details_pace)}: $paceStr"
        }

        chart.invalidate()
    }

    /**
     * One line, broken where there is no cadence (sensor silence, pauses). The title, the
     * dashed average and whether the card shows at all follow [cadenceDisplay] — the stored
     * average, like the tile.
     */
    private fun updateCadenceChart(trackData: TrackData) {
        val chart = cadence.chart
        val points = trackData.points
        val runs = TrackChartBuilder.buildCadenceSeries(points, trackData.pauses)
        cadenceRange = runs.flatten().map { it.cadence.roundToInt() }.let { values ->
            if (values.isEmpty()) null else values.min() to values.max()
        }
        if (cadenceRange == null) {
            applyCadenceDisplay()
            return
        }

        drawRunsOverTime(
            chart = chart,
            runs = runs.map { run -> run.map { Entry(it.timeMinutes, it.cadence) } },
            seriesColor = color(R.color.chart_series_cadence),
            label = context.getString(R.string.chart_cadence_label),
            points = points,
            valuesView = cadence.values
        ) { value -> context.getString(R.string.chart_cadence_value, value) }
        applyCadenceDisplay()
    }

    /**
     * A line over time from [runs], one data set per run (MPAndroidChart joins every entry of a
     * set), on an axis fitted to the values (not from zero: cadence and heart rate sit far above
     * it), the tap values by [valueText] of the rounded value. The cadence and heart rate cards.
     * [runColors]: a colour per entry of each run instead of [seriesColor] (heart rate zones).
     */
    private fun drawRunsOverTime(
        chart: LineChart,
        runs: List<List<Entry>>,
        seriesColor: Int,
        label: String,
        points: List<TrackPoint>,
        valuesView: android.widget.TextView,
        runColors: List<List<Int>>? = null,
        valueText: (Int) -> String
    ) {
        configureInteraction(chart, legend = false)
        val axisTextColor = color(R.color.chart_axis_text)
        val gridColor = color(R.color.chart_grid)

        chart.data = LineData(
            runs.mapIndexed { i, run ->
                LineDataSet(run, label).apply {
                    val entryColors = runColors?.get(i)
                    if (entryColors == null) color = seriesColor else colors = entryColors
                    lineWidth = 2f
                    // A lone point between breaks would draw nothing without its circle
                    setDrawCircles(run.size == 1)
                    setCircleColor(entryColors?.first() ?: seriesColor)
                    circleRadius = 2f
                    setDrawCircleHole(false)
                    setDrawValues(false)
                }
            }
        )

        configureXAxis(chart, axisTextColor, gridColor) { value ->
            context.getString(R.string.chart_time_format, value.toInt())
        }
        configureLeftAxis(chart, axisTextColor, gridColor)
        chart.axisLeft.resetAxisMinimum()
        chart.axisLeft.setDrawLimitLinesBehindData(true)
        chart.axisRight.isEnabled = false
        chart.setDrawMarkers(false)
        // resetAxisMinimum only drops the flag: the 0 set above stays until the axis is fitted again
        chart.notifyDataSetChanged()

        selectByTime(chart, points, valuesView) { e ->
            context.getString(R.string.chart_time_format, e.x.toInt()) + "\n" + valueText(e.y.roundToInt())
        }
    }

    /** The dashed average across a line chart, labelled [label], in the series [colorId]. */
    private fun averageLine(value: Int, label: String, @ColorRes colorId: Int) =
        LimitLine(value.toFloat(), label).apply {
            lineColor = color(colorId)
            lineWidth = 1f
            enableDashedLine(10f, 10f, 0f)
            textColor = color(R.color.chart_axis_text)
            textSize = 10f
            labelPosition = LimitLine.LimitLabelPosition.RIGHT_TOP
        }

    /** Card visibility, title, dashed average and summary from the drawn line and [cadenceDisplay]. */
    private fun applyCadenceDisplay() {
        val range = cadenceRange
        val display = cadenceDisplay
        if (range == null || display == CadenceDisplay.None) {
            cadence.card.visibility = android.view.View.GONE
            return
        }
        cadence.card.visibility = android.view.View.VISIBLE
        val chart = cadence.chart
        val leftAxis = chart.axisLeft
        leftAxis.removeAllLimitLines()
        when (display) {
            is CadenceDisplay.Value -> {
                leftAxis.addLimitLine(
                    averageLine(display.spm, context.getString(R.string.chart_cadence_limit_label, display.spm), R.color.chart_series_cadence)
                )
                cadence.average.text = context.getString(R.string.chart_cadence_avg_format, display.spm)
                cadence.average.contentDescription = CadenceText.averageA11y(context, display.spm)
                chart.contentDescription =
                    context.getString(R.string.chart_cadence_a11y, display.spm, range.first, range.second)
            }
            CadenceDisplay.Pending -> {
                cadence.average.setText(R.string.metric_pending_placeholder)
                cadence.average.contentDescription = context.getString(R.string.workout_details_cadence_pending_a11y)
                chart.contentDescription = context.getString(R.string.workout_details_cadence_pending_a11y)
            }
            CadenceDisplay.None -> Unit
        }
        chart.invalidate()
    }

    /**
     * One line, broken where there is no heart rate (no contact), over pauses and steps longer
     * than 10 s; nothing interpolated. The card shows from two drawn points; the average and
     * maximum in the title and the dashed average follow [heartRateDisplay] — the stored
     * values, like the tile.
     */
    private fun updateHeartRateChart(trackData: TrackData) {
        val chart = heartRate.chart
        val points = trackData.points
        val runs = TrackChartBuilder.buildHeartRateSeries(points, trackData.pauses)
        val drawn = runs.flatten().map { it.bpm.roundToInt() }
        heartRateRange = if (drawn.size < 2) null else drawn.min() to drawn.max()
        if (heartRateRange == null) {
            applyHeartRateDisplay()
            return
        }

        val zones = hrZones
        drawRunsOverTime(
            chart = chart,
            runs = runs.map { run -> run.map { Entry(it.timeMinutes, it.bpm) } },
            seriesColor = color(R.color.chart_series_heart_rate),
            label = context.getString(R.string.chart_heart_rate_label),
            points = points,
            valuesView = heartRate.values,
            runColors = zones?.let { z -> runs.map { run -> zoneColors(run, z) } }
        ) { value ->
            // The tapped point's own zone; the line's colour is its segment's (the mean of two points)
            when {
                zones == null -> context.getString(R.string.chart_heart_rate_value, value)
                zones.zoneOf(value) == HrZones.BELOW -> context.getString(R.string.chart_heart_rate_value_below, value)
                else -> context.getString(R.string.chart_heart_rate_value_zone, value, zones.zoneOf(value))
            }
        }
        applyHeartRateDisplay()
    }

    /**
     * One colour per entry of [run] for MPAndroidChart's per-segment drawing (`colors.size > 1`:
     * segment j takes colour j): the zone of each segment, the last entry repeating the last
     * one; a lone point takes its own zone.
     */
    private fun zoneColors(run: List<HeartRatePoint>, zones: HrZones): List<Int> {
        if (run.size == 1) return listOf(color(HrZoneBadge.colorOf(zones.zoneOf(run[0].bpm.roundToInt()))))
        val segments = TrackChartBuilder.zoneOfSegments(run, zones).map { color(HrZoneBadge.colorOf(it)) }
        return segments + segments.last()
    }

    /**
     * "Z1"…"Z5" dashed at the zone bounds inside the fitted axis ([axisMin]..[axisMax], read
     * after the line is drawn): they are the colour legend.
     */
    private fun zoneBoundLines(zones: HrZones, axisMin: Float, axisMax: Float): List<LimitLine> =
        zones.lowerBounds.withIndex()
            .filter { (_, bound) -> bound.toFloat() in axisMin..axisMax }
            .map { (i, bound) ->
                LimitLine(bound.toFloat(), context.getString(R.string.hr_zone_badge, i + 1)).apply {
                    lineColor = color(R.color.chart_grid)
                    lineWidth = 1f
                    enableDashedLine(4f, 6f, 0f)
                    textColor = color(R.color.chart_axis_text)
                    textSize = 10f
                    labelPosition = LimitLine.LimitLabelPosition.LEFT_TOP
                }
            }

    /** Card visibility, title, dashed average and summary from the drawn line and [heartRateDisplay]. */
    private fun applyHeartRateDisplay() {
        val range = heartRateRange
        if (range == null) {
            heartRate.card.visibility = android.view.View.GONE
            return
        }
        heartRate.card.visibility = android.view.View.VISIBLE
        val chart = heartRate.chart
        val leftAxis = chart.axisLeft
        leftAxis.removeAllLimitLines()
        val zones = hrZones
        zones?.let { zoneBoundLines(it, leftAxis.axisMinimum, leftAxis.axisMaximum).forEach(leftAxis::addLimitLine) }
        when (val display = heartRateDisplay) {
            is HeartRateSummaryDisplay.Value -> {
                leftAxis.addLimitLine(
                    averageLine(
                        display.avgBpm,
                        context.getString(R.string.chart_heart_rate_limit_label, display.avgBpm),
                        // Over a zone-coloured line a red dash would read as Z5
                        if (zones != null) R.color.chart_axis_text else R.color.chart_series_heart_rate
                    )
                )
                heartRate.summary.visibility = android.view.View.VISIBLE
                heartRate.summary.text = display.maxBpm?.let { max ->
                    context.getString(R.string.chart_heart_rate_summary_format, display.avgBpm, max)
                } ?: context.getString(R.string.chart_heart_rate_avg_format, display.avgBpm)
                heartRate.summary.contentDescription = HeartRateText.summaryA11y(context, display.avgBpm, display.maxBpm)
                chart.contentDescription = context.getString(
                    if (zones != null) R.string.chart_heart_rate_zones_a11y else R.string.chart_heart_rate_a11y,
                    display.avgBpm,
                    range.first,
                    range.second
                )
            }
            HeartRateSummaryDisplay.Pending -> {
                heartRate.summary.visibility = android.view.View.VISIBLE
                heartRate.summary.setText(R.string.metric_pending_placeholder)
                heartRate.summary.contentDescription = context.getString(R.string.workout_details_heart_rate_pending_a11y)
                chart.contentDescription = context.getString(R.string.workout_details_heart_rate_pending_a11y)
            }
            // Under a minute of heart rate: the line without an average
            HeartRateSummaryDisplay.None -> {
                heartRate.summary.visibility = android.view.View.GONE
                chart.contentDescription = context.getString(R.string.chart_heart_rate_range_a11y, range.first, range.second)
            }
        }
        chart.invalidate()
    }

    /**
     * One line over distance, broken where there is no altitude and over gaps and dropped
     * stretches (never drawn at zero). Gain, loss and the source in the title follow
     * [elevationDisplay] — the stored values, like the tiles.
     */
    private fun updateElevationChart(trackData: TrackData) {
        val chart = elevation.chart
        val textViewElevationValues = elevation.values
        val points = trackData.points

        // The barometer's line where it is the source, lifted to the GPS scale
        val runs = TrackChartBuilder.buildElevationRuns(points, ElevationSource.of(trackData))
        elevationRange = runs.flatten().map { it.altitudeMeters.roundToInt() }.let { values ->
            if (values.isEmpty()) null else values.min() to values.max()
        }
        if (elevationRange == null) {
            applyElevationDisplay()
            return
        }

        configureInteraction(chart, legend = false)

        val textColor = color(R.color.chart_axis_text)
        val gridColor = color(R.color.chart_grid)
        val seriesColor = color(R.color.chart_series_elevation)
        val label = context.getString(R.string.chart_elevation_title)

        // One data set per run: MPAndroidChart joins every entry of a set
        val dataSets = runs.map { run ->
            LineDataSet(run.map { Entry(it.distanceKm, it.altitudeMeters) }, label).apply {
                color = seriesColor
                lineWidth = 2f
                // A lone point between breaks would draw nothing without its circle
                setDrawCircles(run.size == 1)
                setCircleColor(seriesColor)
                circleRadius = 2f
                setDrawCircleHole(false)
                setDrawValues(false)
                setDrawFilled(true)
                fillColor = seriesColor
                fillAlpha = 50
            }
        }
        chart.data = LineData(dataSets)

        chart.xAxis.position = XAxis.XAxisPosition.BOTTOM
        chart.xAxis.setDrawGridLines(true)
        chart.xAxis.gridColor = gridColor
        chart.xAxis.textColor = textColor
        chart.xAxis.axisLineColor = textColor
        chart.xAxis.granularity = 0.5f
        chart.xAxis.valueFormatter = object : ValueFormatter() {
            override fun getFormattedValue(value: Float): String {
                return context.getString(R.string.chart_elevation_x_format, value)
            }
        }

        val leftAxis = chart.axisLeft
        leftAxis.setDrawGridLines(true)
        leftAxis.gridColor = gridColor
        leftAxis.textColor = textColor
        leftAxis.axisLineColor = textColor
        leftAxis.valueFormatter = object : ValueFormatter() {
            override fun getFormattedValue(value: Float): String {
                return context.getString(R.string.chart_elevation_y_format, value.toInt())
            }
        }

        chart.axisRight.isEnabled = false
        chart.setDrawMarkers(false)

        chart.setOnChartValueSelectedListener(object : OnChartValueSelectedListener {
            override fun onValueSelected(e: Entry?, h: Highlight?) {
                if (e != null) {
                    val selectedDistance = e.x
                    val selectedElevation = e.y

                    val valuesText = context.getString(R.string.chart_elevation_x_format, selectedDistance) + "\n" +
                        context.getString(R.string.chart_elevation_y_format, selectedElevation.toInt())
                    textViewElevationValues.text = valuesText
                    textViewElevationValues.visibility = android.view.View.VISIBLE

                    val pointIndex = SpeedPaceCalculator.findPointIndexByDistance(points, selectedDistance)
                    if (pointIndex >= 0 && pointIndex < points.size) {
                        onPositionSelected(points[pointIndex])
                    }
                }
            }

            override fun onNothingSelected() {
                textViewElevationValues.visibility = android.view.View.GONE
                this@ChartRenderer.onNothingSelected()
            }
        })

        applyElevationDisplay()
    }

    /**
     * Card visibility, gain/loss in the title, the source under it and the TalkBack summary,
     * from the drawn line and [elevationDisplay]. The card shows whenever there is a line.
     */
    private fun applyElevationDisplay() {
        val range = elevationRange
        if (range == null) {
            elevation.card.visibility = android.view.View.GONE
            return
        }
        elevation.card.visibility = android.view.View.VISIBLE
        val chart = elevation.chart
        val from = ElevationText.meters(context, range.first)
        val to = ElevationText.meters(context, range.second)
        when (val display = elevationDisplay) {
            is ElevationDisplay.Value -> {
                elevation.summary.visibility = android.view.View.VISIBLE
                elevation.summary.text =
                    context.getString(R.string.workout_elevation_summary_format, display.gainM, display.lossM)
                val gain = ElevationText.meters(context, display.gainM)
                val loss = ElevationText.meters(context, display.lossM)
                elevation.summary.contentDescription = ElevationText.summaryA11y(context, display.gainM, display.lossM)
                val source = ElevationText.sourceTexts(display.source)?.chartSubtitle
                elevation.source.visibility = if (source != null) android.view.View.VISIBLE else android.view.View.GONE
                source?.let { elevation.source.setText(it) }
                chart.contentDescription = context.getString(R.string.chart_elevation_a11y, gain, loss, from, to)
            }
            ElevationDisplay.Pending -> {
                elevation.summary.visibility = android.view.View.VISIBLE
                elevation.summary.setText(R.string.metric_pending_placeholder)
                elevation.summary.contentDescription = ElevationText.pendingA11y(context)
                elevation.source.visibility = android.view.View.GONE
                chart.contentDescription = context.getString(R.string.chart_elevation_range_a11y, from, to)
            }
            ElevationDisplay.None -> {
                elevation.summary.visibility = android.view.View.GONE
                elevation.source.visibility = android.view.View.GONE
                chart.contentDescription = context.getString(R.string.chart_elevation_range_a11y, from, to)
            }
        }
        chart.invalidate()
    }

    private fun updateSegmentsChart(trackData: TrackData, distanceSegments: Lazy<List<SegmentStats>>) {
        val chart = segmentsChart
        val points = trackData.points

        if (points.size < 2) {
            chart.visibility = android.view.View.GONE
            return
        }

        chart.visibility = android.view.View.VISIBLE
        configureInteraction(chart, legend = true)

        val textColor = color(R.color.chart_axis_text)
        val gridColor = color(R.color.chart_grid)
        val paceColor = color(R.color.chart_series_pace)
        val speedColor = color(R.color.chart_series_speed)

        val isMetric = userPreferences?.isMetricSystem() ?: true
        val unitLabel = if (isMetric) context.getString(R.string.unit_km) else context.getString(R.string.unit_mile)
        val useIntervalPlan = intervalPlanSegments.isNotEmpty()

        val segments = if (useIntervalPlan) {
            SpeedPaceCalculator.buildSegmentsFromPlan(points, intervalPlanSegments, isMetric)
        } else {
            distanceSegments.value
        }
        if (segments.isEmpty()) {
            chart.visibility = android.view.View.GONE
            return
        }

        val axisLabels: List<String> = if (useIntervalPlan) {
            intervalPlanSegments.take(segments.size).mapIndexed { index, seg ->
                val title = seg.localizedTitle(context)
                title.take(8).ifBlank {
                    context.getString(R.string.chart_interval_n, index + 1)
                }
            }
        } else {
            segments.indices.map { index ->
                "$unitLabel ${index + 1}"
            }
        }

        val dataSet: BarDataSet = when (segmentsDisplayMode) {
            SegmentsDisplayMode.PACE -> {
                val entriesPace = segments.mapIndexed { index, seg ->
                    BarEntry(index.toFloat(), seg.paceMinPerUnit.takeIf { it.isFinite() } ?: 0f)
                }
                val paceLabel = if (isMetric) context.getString(R.string.chart_pace_label)
                else "${context.getString(R.string.workout_details_pace)} (мин/$unitLabel)"
                BarDataSet(entriesPace, paceLabel).apply {
                    color = paceColor
                    setDrawValues(true)
                    valueTextColor = textColor
                    valueTextSize = 10f
                    valueFormatter = object : ValueFormatter() {
                        override fun getFormattedValue(value: Float): String {
                            return SpeedPaceCalculator.formatPaceMmSs(value)
                        }
                    }
                }
            }
            SegmentsDisplayMode.SPEED -> {
                val entriesSpeed = segments.mapIndexed { index, seg ->
                    BarEntry(index.toFloat(), seg.speedDisplay.takeIf { it.isFinite() } ?: 0f)
                }
                val speedUnit = if (isMetric) context.getString(R.string.unit_kmh) else context.getString(R.string.unit_mph)
                val speedLabel = if (isMetric) context.getString(R.string.chart_speed_label)
                else "${context.getString(R.string.workout_details_speed)} ($speedUnit)"
                BarDataSet(entriesSpeed, speedLabel).apply {
                    color = speedColor
                    setDrawValues(true)
                    valueTextColor = textColor
                    valueTextSize = 10f
                    valueFormatter = object : ValueFormatter() {
                        override fun getFormattedValue(value: Float): String {
                            return String.format("%.1f", value)
                        }
                    }
                }
            }
        }

        val barData = BarData(dataSet).apply {
            barWidth = 0.6f
        }
        chart.data = barData

        chart.xAxis.position = XAxis.XAxisPosition.BOTTOM
        chart.xAxis.setDrawGridLines(true)
        chart.xAxis.gridColor = gridColor
        chart.xAxis.textColor = textColor
        chart.xAxis.axisLineColor = textColor
        chart.xAxis.granularity = 1f
        chart.xAxis.setLabelCount(axisLabels.size.coerceAtMost(8), false)
        chart.xAxis.valueFormatter = object : ValueFormatter() {
            override fun getFormattedValue(value: Float): String {
                val index = value.toInt()
                return axisLabels.getOrNull(index) ?: ""
            }
        }

        val leftAxis = chart.axisLeft
        leftAxis.setDrawGridLines(true)
        leftAxis.gridColor = gridColor
        leftAxis.textColor = textColor
        leftAxis.axisLineColor = textColor
        leftAxis.axisMinimum = 0f

        chart.axisRight.isEnabled = false
        chart.legend.textColor = textColor
        chart.setFitBars(true)
        chart.setDrawMarkers(false)
        chart.contentDescription = ChartSummaryText.segmentsA11y(
            context,
            segments,
            isMetric,
            speed = segmentsDisplayMode == SegmentsDisplayMode.SPEED,
            plan = if (useIntervalPlan) intervalPlanSegments else emptyList()
        ) ?: context.getString(R.string.workout_details_pace_speed_title)

        // Cache segments list for touch handler
        val cachedSegments = segments

        chart.setOnChartValueSelectedListener(object : OnChartValueSelectedListener {
            override fun onValueSelected(e: Entry?, h: Highlight?) {
                if (e != null) {
                    val segmentIndex = e.x.toInt()
                    if (segmentIndex >= 0 && segmentIndex < cachedSegments.size) {
                        val seg = cachedSegments[segmentIndex]
                        val startPoint = points.getOrNull(seg.startIndex) ?: return
                        val endPoint = points.getOrNull(seg.endIndex) ?: return
                        onSegmentSelected(startPoint, endPoint)
                    }
                }
            }

            override fun onNothingSelected() {
                this@ChartRenderer.onNothingSelected()
            }
        })

        chart.invalidate()
    }

    /** Touch, drag and pinch zoom on, no description label. */
    private fun configureInteraction(chart: BarLineChartBase<*>, legend: Boolean) {
        chart.description.isEnabled = false
        chart.setTouchEnabled(true)
        chart.setDragEnabled(true)
        chart.setScaleEnabled(true)
        chart.setPinchZoom(true)
        chart.legend.isEnabled = legend
    }

    /**
     * A tap on a chart over time: [text] of the entry in [valuesView] and the nearest track
     * point on the map; a tap off the line hides both.
     */
    private fun selectByTime(
        chart: LineChart,
        points: List<TrackPoint>,
        valuesView: android.widget.TextView,
        text: (Entry) -> String
    ) {
        chart.setOnChartValueSelectedListener(object : OnChartValueSelectedListener {
            override fun onValueSelected(e: Entry?, h: Highlight?) {
                if (e == null) return
                val pointIndex = SpeedPaceCalculator.findNearestPointIndexByTime(points, e.x)
                if (pointIndex < 0 || pointIndex >= points.size) return
                valuesView.text = text(e)
                valuesView.visibility = android.view.View.VISIBLE
                onPositionSelected(points[pointIndex])
            }

            override fun onNothingSelected() {
                valuesView.visibility = android.view.View.GONE
                // The renderer's callback, not this listener's own method
                this@ChartRenderer.onNothingSelected()
            }
        })
    }

    private fun configureXAxis(
        chart: LineChart,
        textColor: Int,
        gridColor: Int,
        formatter: (Float) -> String
    ) {
        val xAxis = chart.xAxis
        xAxis.position = XAxis.XAxisPosition.BOTTOM
        xAxis.setDrawGridLines(true)
        xAxis.gridColor = gridColor
        xAxis.textColor = textColor
        xAxis.axisLineColor = textColor
        xAxis.granularity = 1f
        xAxis.valueFormatter = object : ValueFormatter() {
            override fun getFormattedValue(value: Float): String {
                return formatter(value)
            }
        }
    }

    private fun configureLeftAxis(chart: LineChart, textColor: Int, gridColor: Int) {
        val leftAxis = chart.axisLeft
        leftAxis.setDrawGridLines(true)
        leftAxis.gridColor = gridColor
        leftAxis.textColor = textColor
        leftAxis.axisLineColor = textColor
        leftAxis.axisMinimum = 0f
    }

    /**
     * A chart colour from resources: `chart_*` in values/colors.xml, redefined per theme in
     * values-night where the dark card needs it. The tap-values panels take theirs in the layout.
     */
    private fun color(@ColorRes id: Int): Int = ContextCompat.getColor(context, id)
}
