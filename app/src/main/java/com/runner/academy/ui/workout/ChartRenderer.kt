package com.runner.academy.ui.workout

import android.graphics.Color
import android.content.res.Configuration
import com.runner.academy.R
import com.runner.academy.data.TrackData
import com.runner.academy.data.TrackPoint
import com.runner.academy.data.localizedTitle
import com.runner.academy.util.SpeedPaceCalculator
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.components.YAxis
import com.github.mikephil.charting.data.*
import com.github.mikephil.charting.formatter.ValueFormatter
import com.github.mikephil.charting.highlight.Highlight
import com.github.mikephil.charting.listener.OnChartValueSelectedListener

/**
 * Handles all chart configuration and rendering for workout detail screen.
 * Manages elevation profile, speed/pace charts, and segment bar charts.
 */
class ChartRenderer(
    private val paceSpeedHeartChart: LineChart,
    private val elevationChart: LineChart,
    private val segmentsChart: BarChart,
    private val textViewPaceSpeedValues: android.widget.TextView,
    private val textViewElevationValues: android.widget.TextView,
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

    enum class SegmentsDisplayMode {
        PACE, SPEED
    }

    fun updateAllCharts(trackData: TrackData) {
        if (trackData.points.isEmpty()) return
        try {
            updatePaceSpeedHeartChart(trackData)
            updateElevationChart(trackData)
            updateSegmentsChart(trackData)
        } catch (e: Exception) {
            android.util.Log.e("ChartRenderer", "Failed to update charts", e)
            paceSpeedHeartChart.visibility = android.view.View.GONE
            elevationChart.visibility = android.view.View.GONE
            segmentsChart.visibility = android.view.View.GONE
        }
    }

    fun updateSegmentsChartOnly(trackData: TrackData) {
        try {
            updateSegmentsChart(trackData)
        } catch (e: Exception) {
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
        chart.description.isEnabled = false
        chart.setTouchEnabled(true)
        chart.setDragEnabled(true)
        chart.setScaleEnabled(true)
        chart.setPinchZoom(true)
        chart.legend.isEnabled = true

        val isDark = isDarkTheme()
        val textColor = if (isDark) Color.WHITE else Color.BLACK
        val gridColor = if (isDark) Color.parseColor("#40FFFFFF") else Color.parseColor("#40000000")

        val isMetric = userPreferences?.isMetricSystem() ?: true
        val paceSpeedSeries = SpeedPaceCalculator.buildPaceSpeedSeries(points, isMetric)
        if (paceSpeedSeries.isEmpty()) {
            chart.visibility = android.view.View.GONE
            return
        }

        val entriesPace = paceSpeedSeries.map { Entry(it.timeMinutes, it.paceMinPerUnit) }
        val entriesSpeed = paceSpeedSeries.map { Entry(it.timeMinutes, it.speedDisplay) }

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

        // Cache for touch handler
        val cachedSeries = paceSpeedSeries

        chart.setOnChartValueSelectedListener(object : OnChartValueSelectedListener {
            override fun onValueSelected(e: Entry?, h: Highlight?) {
                if (e != null) {
                    val pointIndex = SpeedPaceCalculator.findNearestPointIndexByTime(points, e.x.toFloat())
                    if (pointIndex >= 0 && pointIndex < points.size) {
                        val selectedPoint = points[pointIndex]

                        // Find nearest series entry for display values
                        val nearestEntry = cachedSeries.minByOrNull { kotlin.math.abs(it.timeMinutes - e.x.toFloat()) }

                        val timeMinutes = e.x.toInt()
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

                        val valuesText = context.getString(R.string.chart_time_format, timeMinutes) + "\n" +
                            "${context.getString(R.string.workout_details_speed)}: $speedStr\n" +
                            "${context.getString(R.string.workout_details_pace)}: $paceStr"
                        textViewPaceSpeedValues.text = valuesText
                        textViewPaceSpeedValues.visibility = android.view.View.VISIBLE

                        onPositionSelected(selectedPoint)
                    }
                }
            }

            override fun onNothingSelected() {
                textViewPaceSpeedValues.visibility = android.view.View.GONE
                onNothingSelected()
            }
        })

        chart.invalidate()
    }

    private fun updateElevationChart(trackData: TrackData) {
        val chart = elevationChart
        val points = trackData.points

        if (points.isEmpty() || points.all { it.altitude == null }) {
            chart.visibility = android.view.View.GONE
            return
        }

        chart.visibility = android.view.View.VISIBLE
        chart.description.isEnabled = false
        chart.setTouchEnabled(true)
        chart.setDragEnabled(true)
        chart.setScaleEnabled(true)
        chart.setPinchZoom(true)
        chart.legend.isEnabled = false

        val isDark = isDarkTheme()
        val textColor = if (isDark) Color.WHITE else Color.BLACK
        val gridColor = if (isDark) Color.parseColor("#40FFFFFF") else Color.parseColor("#40000000")

        val elevationSeries = SpeedPaceCalculator.buildElevationSeries(points)
        if (elevationSeries.isEmpty()) {
            chart.visibility = android.view.View.GONE
            return
        }

        val entries = elevationSeries.map { Entry(it.distanceKm, it.altitudeMeters) }

        val dataSet = LineDataSet(entries, context.getString(R.string.chart_elevation_title)).apply {
            color = Color.parseColor("#4CAF50")
            lineWidth = 2f
            setCircleColor(Color.parseColor("#4CAF50"))
            setDrawCircles(false)
            setDrawValues(false)
            setDrawFilled(true)
            fillColor = Color.parseColor("#4CAF50")
            fillAlpha = 50
        }

        val lineData = LineData(dataSet)
        chart.data = lineData

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
                onNothingSelected()
            }
        })

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
        chart.description.isEnabled = false
        chart.setTouchEnabled(true)
        chart.setDragEnabled(true)
        chart.setScaleEnabled(true)
        chart.setPinchZoom(true)
        chart.legend.isEnabled = true

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
                val entriesPace = segments.mapIndexed { index, seg -> BarEntry(index.toFloat(), seg.paceMinPerUnit) }
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
                val entriesSpeed = segments.mapIndexed { index, seg -> BarEntry(index.toFloat(), seg.speedDisplay) }
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
                onNothingSelected()
            }
        })

        chart.invalidate()
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
