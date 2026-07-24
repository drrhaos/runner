package com.drrhaos.runner.ui.workout

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.drrhaos.runner.R
import com.drrhaos.runner.data.TrackData
import com.drrhaos.runner.data.TrackPoint
import kotlin.math.max
import kotlin.math.min

/**
 * Lightweight track silhouette for lists — no MapView / network tiles.
 */
class RoutePreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val MAX_DRAW_POINTS = 240
        private const val CONTENT_PADDING_DP = 10f
        private const val TRACK_STROKE_DP = 2.5f
        private const val START_RADIUS_DP = 3.5f
    }

    private val trackPath = Path()
    private val density = resources.displayMetrics.density

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.route_preview_background)
    }

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = TRACK_STROKE_DP * density
        color = ContextCompat.getColor(context, R.color.route_preview_track)
    }

    private val startPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.route_preview_start)
    }

    private val endPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.route_preview_end)
    }

    private var points: List<TrackPoint> = emptyList()
    private var startX = 0f
    private var startY = 0f
    private var endX = 0f
    private var endY = 0f
    private var hasGeometry = false

    fun setTrackData(trackData: TrackData?) {
        points = trackData?.points.orEmpty()
        rebuildPath(width, height)
        invalidate()
    }

    fun setTrackPoints(trackPoints: List<TrackPoint>) {
        points = trackPoints
        rebuildPath(width, height)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildPath(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        val radius = 8f * density
        val bounds = RectF(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(bounds, radius, radius, backgroundPaint)

        if (!hasGeometry) return

        val clipPath = Path().apply {
            addRoundRect(bounds, radius, radius, Path.Direction.CW)
        }
        val save = canvas.save()
        canvas.clipPath(clipPath)
        canvas.drawPath(trackPath, trackPaint)
        val markerR = START_RADIUS_DP * density
        canvas.drawCircle(startX, startY, markerR, startPaint)
        canvas.drawCircle(endX, endY, markerR, endPaint)
        canvas.restoreToCount(save)
    }

    private fun rebuildPath(viewWidth: Int, viewHeight: Int) {
        trackPath.reset()
        hasGeometry = false
        if (viewWidth <= 0 || viewHeight <= 0 || points.isEmpty()) return

        val sampled = downsample(points, MAX_DRAW_POINTS)
        var minLat = Double.POSITIVE_INFINITY
        var maxLat = Double.NEGATIVE_INFINITY
        var minLon = Double.POSITIVE_INFINITY
        var maxLon = Double.NEGATIVE_INFINITY
        for (point in sampled) {
            minLat = min(minLat, point.latitude)
            maxLat = max(maxLat, point.latitude)
            minLon = min(minLon, point.longitude)
            maxLon = max(maxLon, point.longitude)
        }

        val latSpan = max(maxLat - minLat, 1e-5)
        val lonSpan = max(maxLon - minLon, 1e-5)
        val padding = CONTENT_PADDING_DP * density
        val usableW = viewWidth - padding * 2
        val usableH = viewHeight - padding * 2
        if (usableW <= 0f || usableH <= 0f) return

        // Keep aspect ratio of the route bounding box
        val scale = min(usableW / lonSpan.toFloat(), usableH / latSpan.toFloat())
        val drawW = lonSpan.toFloat() * scale
        val drawH = latSpan.toFloat() * scale
        val offsetX = padding + (usableW - drawW) / 2f
        val offsetY = padding + (usableH - drawH) / 2f

        fun mapX(lon: Double): Float = offsetX + ((lon - minLon) / lonSpan).toFloat() * drawW
        fun mapY(lat: Double): Float = offsetY + ((maxLat - lat) / latSpan).toFloat() * drawH

        var started = false
        for (point in sampled) {
            val x = mapX(point.longitude)
            val y = mapY(point.latitude)
            if (!started) {
                trackPath.moveTo(x, y)
                startX = x
                startY = y
                started = true
            } else if (point.afterGap) {
                trackPath.moveTo(x, y)
            } else {
                trackPath.lineTo(x, y)
            }
            endX = x
            endY = y
        }
        hasGeometry = started
    }

    private fun downsample(source: List<TrackPoint>, maxPoints: Int): List<TrackPoint> {
        if (source.size <= maxPoints) return source
        val result = ArrayList<TrackPoint>(maxPoints)
        val step = (source.size - 1).toFloat() / (maxPoints - 1)
        var i = 0
        while (i < maxPoints) {
            val index = (i * step).toInt().coerceIn(0, source.lastIndex)
            result.add(source[index])
            i++
        }
        if (result.last() != source.last()) {
            result[result.lastIndex] = source.last()
        }
        return result
    }
}
