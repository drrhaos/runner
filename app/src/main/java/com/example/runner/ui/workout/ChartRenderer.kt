package com.example.runner.ui.workout

import android.graphics.Color
import android.location.Location
import android.content.res.Configuration
import com.example.runner.R
import com.example.runner.data.TrackData
import com.example.runner.data.TrackPoint
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.components.YAxis
import com.github.mikephil.charting.data.*
import com.github.mikephil.charting.formatter.ValueFormatter
import com.github.mikephil.charting.highlight.Highlight
import com.github.mikephil.charting.listener.OnChartValueSelectedListener
import org.osmdroid.util.GeoPoint

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
    private val userPreferences: com.example.runner.util.UserPreferences?,
    private val onPositionSelected: (TrackPoint) -> Unit,
    private val onSegmentSelected: (startPoint: TrackPoint, endPoint: TrackPoint) -> Unit,
    private val onNothingSelected: () -> Unit
) {

    var segmentsDisplayMode: SegmentsDisplayMode = SegmentsDisplayMode.PACE
        set(value) {
            field = value
        }

    enum class SegmentsDisplayMode {
        PACE, SPEED
    }

    fun updateAllCharts(trackData: TrackData) {
        if (trackData.points.isEmpty()) return
        updatePaceSpeedHeartChart(trackData)
        updateElevationChart(trackData)
        updateSegmentsChart(trackData)
    }

    fun updateSegmentsChartOnly(trackData: TrackData) {
        updateSegmentsChart(trackData)
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

        val entriesPace = mutableListOf<Entry>()
        val entriesSpeed = mutableListOf<Entry>()

        var cumulativeDistance = 0f
        val startTime = points.firstOrNull()?.timestamp ?: 0L

        for (i in 0 until points.size) {
            val point = points[i]

            if (i > 0) {
                val prevPoint = points[i - 1]
                val location1 = Location("").apply {
                    latitude = prevPoint.latitude
                    longitude = prevPoint.longitude
                }
                val location2 = Location("").apply {
                    latitude = point.latitude
                    longitude = point.longitude
                }
                cumulativeDistance += location1.distanceTo(location2) / 1000f
            }

            val speedMs = point.speed ?: 0f
            val speedKmh = speedMs * 3.6f
            val pace = if (speedKmh > 0) 60f / speedKmh else 0f

            val timeMinutes = if (startTime > 0) (point.timestamp - startTime) / 60000f else 0f

            entriesPace.add(Entry(timeMinutes, pace))
            entriesSpeed.add(Entry(timeMinutes, speedKmh))
        }

        val dataSetPace = LineDataSet(entriesPace, context.getString(R.string.chart_pace_label)).apply {
            color = Color.parseColor("#FF9800")
            lineWidth = 2f
            setCircleColor(Color.parseColor("#FF9800"))
            setDrawCircles(false)
            setDrawValues(false)
            axisDependency = YAxis.AxisDependency.LEFT
        }

        val dataSetSpeed = LineDataSet(entriesSpeed, context.getString(R.string.chart_speed_label)).apply {
            color = Color.parseColor("#2196F3")
            lineWidth = 2f
            setCircleColor(Color.parseColor("#2196F3"))
            setDrawCircles(false)
            setDrawValues(false)
            axisDependency = YAxis.AxisDependency.LEFT
        }

        val lineData = LineData(dataSetPace, dataSetSpeed)
        chart.data = lineData

        configureXAxis(chart, textColor, gridColor) { value ->
            val minutes = value.toInt()
            context.getString(R.string.chart_time_format, minutes)
        }

        configureLeftAxis(chart, textColor, gridColor)

        chart.axisRight.isEnabled = false
        chart.legend.textColor = textColor
        chart.setDrawMarkers(false)

        textViewPaceSpeedValues.setBackgroundColor(
            if (isDark) Color.parseColor("#E0FFFFFF") else Color.parseColor("#E0000000")
        )
        textViewPaceSpeedValues.setTextColor(
            if (isDark) Color.BLACK else Color.WHITE
        )

        chart.setOnChartValueSelectedListener(object : OnChartValueSelectedListener {
            override fun onValueSelected(e: Entry?, h: Highlight?) {
                if (e != null) {
                    val startTime = points.firstOrNull()?.timestamp ?: 0L
                    val selectedTime = startTime + (e.x * 60000).toLong()
                    val selectedPoint = points.minByOrNull {
                        kotlin.math.abs(it.timestamp - selectedTime)
                    }

                    if (selectedPoint != null) {
                        val speedMs = selectedPoint.speed ?: 0f
                        val speedKmh = speedMs * 3.6f
                        val pace = if (speedKmh > 0) 60f / speedKmh else 0f

                        val timeMinutes = e.x.toInt()
                        val paceMinutes = pace.toInt()
                        val paceSeconds = ((pace - paceMinutes) * 60).toInt().coerceIn(0, 59)

                        val valuesText = context.getString(R.string.chart_time_format, timeMinutes) + "\n" +
                            "${context.getString(R.string.workout_details_speed)}: %.0f м/сек\n".format(speedMs) +
                            "${context.getString(R.string.workout_details_pace)}: %d:%02d мин/км".format(paceMinutes, paceSeconds)
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

        val entries = mutableListOf<Entry>()
        var cumulativeDistance = 0f

        for (i in 0 until points.size) {
            val point = points[i]

            if (i > 0) {
                val prevPoint = points[i - 1]
                val location1 = Location("").apply {
                    latitude = prevPoint.latitude
                    longitude = prevPoint.longitude
                }
                val location2 = Location("").apply {
                    latitude = point.latitude
                    longitude = point.longitude
                }
                cumulativeDistance += location1.distanceTo(location2) / 1000f
            }

            val altitude = point.altitude ?: 0.0
            entries.add(Entry(cumulativeDistance, altitude.toFloat()))
        }

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

                    var cumulativeDistance = 0f
                    var selectedPoint: TrackPoint? = null

                    for (i in 0 until points.size) {
                        val point = points[i]

                        if (i > 0) {
                            val prevPoint = points[i - 1]
                            val location1 = Location("").apply {
                                latitude = prevPoint.latitude
                                longitude = prevPoint.longitude
                            }
                            val location2 = Location("").apply {
                                latitude = point.latitude
                                longitude = point.longitude
                            }
                            cumulativeDistance += location1.distanceTo(location2) / 1000f
                        }

                        if (cumulativeDistance >= selectedDistance) {
                            selectedPoint = point
                            break
                        }
                    }

                    if (selectedPoint == null && points.isNotEmpty()) {
                        selectedPoint = points.last()
                    }

                    selectedPoint?.let { point ->
                        onPositionSelected(point)
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
        val segmentSize = if (isMetric) 1.0f else 1.60934f
        val unitLabel = if (isMetric) context.getString(R.string.unit_km) else context.getString(R.string.unit_mile)
        val segments = mutableListOf<Pair<Float, Float>>()
        var currentSegmentDistance = 0f
        var segmentStartTime = points.firstOrNull()?.timestamp ?: 0L

        for (i in 1 until points.size) {
            val prevPoint = points[i - 1]
            val point = points[i]

            val location1 = Location("").apply {
                latitude = prevPoint.latitude
                longitude = prevPoint.longitude
            }
            val location2 = Location("").apply {
                latitude = point.latitude
                longitude = point.longitude
            }
            val segmentDistance = location1.distanceTo(location2) / 1000f
            currentSegmentDistance += segmentDistance

            if (currentSegmentDistance >= segmentSize || i == points.size - 1) {
                val segmentDuration = point.timestamp - segmentStartTime
                val segmentDurationMinutes = segmentDuration / 60000f
                val pace = if (currentSegmentDistance > 0) segmentDurationMinutes / currentSegmentDistance else 0f
                var speed = if (segmentDurationMinutes > 0) currentSegmentDistance / (segmentDurationMinutes / 60f) else 0f

                if (!isMetric) {
                    speed = speed / 1.60934f
                }

                segments.add(Pair(pace, speed))
                currentSegmentDistance = 0f
                segmentStartTime = point.timestamp
            }
        }

        if (segments.isEmpty()) {
            chart.visibility = android.view.View.GONE
            return
        }

        val dataSet: BarDataSet = when (segmentsDisplayMode) {
            SegmentsDisplayMode.PACE -> {
                val entriesPace = segments.mapIndexed { index, pair -> BarEntry(index.toFloat(), pair.first) }
                val paceLabel = if (isMetric) context.getString(R.string.chart_pace_label)
                else "${context.getString(R.string.workout_details_pace)} (мин/$unitLabel)"
                BarDataSet(entriesPace, paceLabel).apply {
                    color = Color.parseColor("#FF9800")
                    setDrawValues(true)
                    valueTextColor = textColor
                    valueTextSize = 10f
                    valueFormatter = object : ValueFormatter() {
                        override fun getFormattedValue(value: Float): String {
                            return String.format("%.2f", value)
                        }
                    }
                }
            }
            SegmentsDisplayMode.SPEED -> {
                val entriesSpeed = segments.mapIndexed { index, pair -> BarEntry(index.toFloat(), pair.second) }
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
        chart.xAxis.valueFormatter = object : ValueFormatter() {
            override fun getFormattedValue(value: Float): String {
                val segmentNum = value.toInt() + 1
                return "$unitLabel $segmentNum"
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

        chart.setOnChartValueSelectedListener(object : OnChartValueSelectedListener {
            override fun onValueSelected(e: Entry?, h: Highlight?) {
                if (e != null) {
                    val segmentIndex = e.x.toInt()

                    var currentSegmentDistance = 0f
                    var segmentStartIndex = 0
                    var segmentCount = 0
                    var segmentStartPoint: TrackPoint? = null
                    var segmentEndPoint: TrackPoint? = null

                    for (i in 1 until points.size) {
                        val prevPoint = points[i - 1]
                        val point = points[i]

                        if (segmentCount == segmentIndex && segmentStartPoint == null) {
                            segmentStartPoint = points[segmentStartIndex]
                        }

                        val location1 = Location("").apply {
                            latitude = prevPoint.latitude
                            longitude = prevPoint.longitude
                        }
                        val location2 = Location("").apply {
                            latitude = point.latitude
                            longitude = point.longitude
                        }
                        val segmentDistance = location1.distanceTo(location2) / 1000f
                        currentSegmentDistance += segmentDistance

                        if (currentSegmentDistance >= segmentSize || i == points.size - 1) {
                            if (segmentCount == segmentIndex) {
                                segmentStartPoint = points[segmentStartIndex]
                                segmentEndPoint = point

                                segmentStartPoint?.let { start ->
                                    segmentEndPoint?.let { end ->
                                        onSegmentSelected(start, end)
                                    }
                                }

                                break
                            }
                            segmentCount++
                            segmentStartIndex = i
                            currentSegmentDistance = 0f
                        }
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
