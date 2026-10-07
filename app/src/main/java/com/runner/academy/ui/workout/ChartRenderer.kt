package com.runner.academy.ui.workout

import android.graphics.Color
import android.content.res.Configuration
import androidx.core.content.ContextCompat
import com.runner.academy.R
import com.runner.academy.data.TrackData
import com.runner.academy.data.TrackPoint
import com.runner.academy.data.localizedTitle
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
            updatePaceSpeedHeartChart(trackData)
            updateCadenceChart(trackData)
            updateElevationChart(trackData)
            updateSegmentsChart(trackData)
        } catch (e: Throwable) {
            android.util.Log.e("ChartRenderer", "Failed to update charts", e)
            paceSpeedHeartChart.visibility = android.view.View.GONE
            cadence.card.visibility = android.view.View.GONE
            elevation.card.visibility = android.view.View.GONE
            segmentsChart.visibility = android.view.View.GONE
        }
    }

    fun updateSegmentsChartOnly(trackData: TrackData) {
        try {
            updateSegmentsChart(trackData)
        } catch (e: Throwable) {
            android.util.Log.e("ChartRenderer", "Failed to update segments chart", e)
            segmentsChart.visibility = android.view.View.GONE
        }
    }

    private fun updatePaceSpeedHeartChart(trackData: TrackData) {
        val chart = paceSpeedHeartChart
        val points = trackData.points

        if (points.size < 2) {
            chart.visibility = android.view.View.GONE
            return
        }

        chart.visibility = android.view.View.VISIBLE
        configureInteraction(chart, legend = true)

        val isDark = isDarkTheme()
        val textColor = if (isDark) Color.WHITE else Color.BLACK
        val gridColor = if (isDark) Color.parseColor("#40FFFFFF") else Color.parseColor("#40000000")

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
            color = Color.parseColor("#FF9800")
            lineWidth = 2f
            setCircleColor(Color.parseColor("#FF9800"))
            setDrawCircles(false)
            setDrawValues(false)
            axisDependency = YAxis.AxisDependency.LEFT
        }

        val dataSetSpeed = LineDataSet(entriesSpeed, speedLabel).apply {
            color = Color.parseColor("#2196F3")
            lineWidth = 2f
            setCircleColor(Color.parseColor("#2196F3"))
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

        configureLeftAxis(chart, textColor, gridColor)
        chart.axisLeft.textColor = Color.parseColor("#FF9800")

        val rightAxis = chart.axisRight
        rightAxis.isEnabled = true
        rightAxis.setDrawGridLines(false)
        rightAxis.textColor = Color.parseColor("#2196F3")
        rightAxis.axisLineColor = textColor

        chart.legend.textColor = textColor
        chart.setDrawMarkers(false)

        textViewPaceSpeedValues.setBackgroundColor(
            if (isDark) Color.parseColor("#E0FFFFFF") else Color.parseColor("#E0000000")
        )
        textViewPaceSpeedValues.setTextColor(
            if (isDark) Color.BLACK else Color.WHITE
        )

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

        configureInteraction(chart, legend = false)

        // Theme-aware resources: the series hue and the label/separator of the palette
        val seriesColor = ContextCompat.getColor(context, R.color.chart_series_cadence)
        val axisTextColor = ContextCompat.getColor(context, R.color.ios_label)
        val gridColor = ContextCompat.getColor(context, R.color.ios_separator)
        val label = context.getString(R.string.chart_cadence_label)

        // One data set per run: MPAndroidChart joins every entry of a set
        val dataSets = runs.map { run ->
            LineDataSet(run.map { Entry(it.timeMinutes, it.cadence) }, label).apply {
                color = seriesColor
                lineWidth = 2f
                // A lone point between breaks would draw nothing without its circle
                setDrawCircles(run.size == 1)
                setCircleColor(seriesColor)
                circleRadius = 2f
                setDrawCircleHole(false)
                setDrawValues(false)
            }
        }
        chart.data = LineData(dataSets)

        configureXAxis(chart, axisTextColor, gridColor) { value ->
            context.getString(R.string.chart_time_format, value.toInt())
        }
        configureLeftAxis(chart, axisTextColor, gridColor)
        // Cadence sits around 150–190: an axis from zero would flatten the line
        chart.axisLeft.resetAxisMinimum()
        chart.axisLeft.setDrawLimitLinesBehindData(true)
        chart.axisRight.isEnabled = false
        chart.setDrawMarkers(false)

        selectByTime(chart, points, cadence.values) { e ->
            context.getString(R.string.chart_time_format, e.x.toInt()) + "\n" +
                context.getString(R.string.chart_cadence_value, e.y.roundToInt())
        }
        applyCadenceDisplay()
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
                    LimitLine(display.spm.toFloat(), context.getString(R.string.chart_cadence_limit_label, display.spm)).apply {
                        lineColor = ContextCompat.getColor(context, R.color.chart_series_cadence)
                        lineWidth = 1f
                        enableDashedLine(10f, 10f, 0f)
                        textColor = ContextCompat.getColor(context, R.color.ios_label)
                        textSize = 10f
                        labelPosition = LimitLine.LimitLabelPosition.RIGHT_TOP
                    }
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
     * One line over distance, broken where there is no altitude and over gaps and dropped
     * stretches (never drawn at zero). Gain, loss and the source in the title follow
     * [elevationDisplay] — the stored values, like the tiles.
     */
    private fun updateElevationChart(trackData: TrackData) {
        val chart = elevation.chart
        val textViewElevationValues = elevation.values
        val points = trackData.points

        val runs = TrackChartBuilder.buildElevationRuns(points)
        elevationRange = runs.flatten().map { it.altitudeMeters.roundToInt() }.let { values ->
            if (values.isEmpty()) null else values.min() to values.max()
        }
        if (elevationRange == null) {
            applyElevationDisplay()
            return
        }

        configureInteraction(chart, legend = false)

        val isDark = isDarkTheme()
        val textColor = if (isDark) Color.WHITE else Color.BLACK
        val gridColor = if (isDark) Color.parseColor("#40FFFFFF") else Color.parseColor("#40000000")
        val seriesColor = Color.parseColor("#4CAF50")
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

        textViewElevationValues.setBackgroundColor(
            if (isDark) Color.parseColor("#E0FFFFFF") else Color.parseColor("#E0000000")
        )
        textViewElevationValues.setTextColor(
            if (isDark) Color.BLACK else Color.WHITE
        )

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
                elevation.summary.contentDescription =
                    ElevationText.gainA11y(context, display.gainM) + ", " + ElevationText.lossA11y(context, display.lossM)
                val source = ElevationText.sourceShort(display.source)
                elevation.source.visibility = if (source != null) android.view.View.VISIBLE else android.view.View.GONE
                source?.let { elevation.source.setText(it) }
                chart.contentDescription = context.getString(R.string.chart_elevation_a11y, gain, loss, from, to)
            }
            ElevationDisplay.Pending -> {
                elevation.summary.visibility = android.view.View.VISIBLE
                elevation.summary.setText(R.string.metric_pending_placeholder)
                elevation.summary.contentDescription = context.getString(R.string.workout_elevation_pending_a11y)
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

    private fun updateSegmentsChart(trackData: TrackData) {
        val chart = segmentsChart
        val points = trackData.points

        if (points.size < 2) {
            chart.visibility = android.view.View.GONE
            return
        }

        chart.visibility = android.view.View.VISIBLE
        configureInteraction(chart, legend = true)

        val isDark = isDarkTheme()
        val textColor = if (isDark) Color.WHITE else Color.BLACK
        val gridColor = if (isDark) Color.parseColor("#40FFFFFF") else Color.parseColor("#40000000")

        val isMetric = userPreferences?.isMetricSystem() ?: true
        val unitLabel = if (isMetric) context.getString(R.string.unit_km) else context.getString(R.string.unit_mile)
        val useIntervalPlan = intervalPlanSegments.isNotEmpty()

        val segments = if (useIntervalPlan) {
            SpeedPaceCalculator.buildSegmentsFromPlan(points, intervalPlanSegments, isMetric)
        } else {
            SpeedPaceCalculator.buildSegments(points, isMetric)
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
                    color = Color.parseColor("#FF9800")
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
                    color = Color.parseColor("#2196F3")
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

    private fun isDarkTheme(): Boolean {
        val nightModeFlags = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return nightModeFlags == Configuration.UI_MODE_NIGHT_YES
    }
}
