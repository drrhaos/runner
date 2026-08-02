package com.runner.academy.ui.workout

import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import com.runner.academy.data.TrackData
import com.runner.academy.data.TrackPoint
import com.runner.academy.util.OsmMapConfig
import com.runner.academy.util.OsmMapTiles
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline
import kotlin.math.max

/**
 * Small read-only OSM map with the workout track fitted to bounds.
 * Gestures are disabled so the list row stays clickable.
 */
class RouteMapPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    companion object {
        private const val MAX_DRAW_POINTS = 180
        private const val LINE_WIDTH = 6f
        private const val BOUNDS_PADDING_PX = 24
        private const val CORNER_RADIUS_DP = 8f
    }

    private val mapView = MapView(context)
    private var trackPolyline: Polyline? = null
    private var boundTrackKey: String? = null
    private var pendingFitPoints: List<GeoPoint> = emptyList()
    private var clickRelay: (() -> Unit)? = null

    init {
        OsmMapConfig.apply(context)
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                val radius = CORNER_RADIUS_DP * resources.displayMetrics.density
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }

        mapView.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        mapView.setMultiTouchControls(false)
        mapView.setBuiltInZoomControls(false)
        mapView.isClickable = false
        mapView.isFocusable = false
        mapView.isHorizontalScrollBarEnabled = false
        mapView.isVerticalScrollBarEnabled = false
        mapView.setUseDataConnection(true)
        mapView.controller.setZoom(14.0)
        OsmMapTiles.applyForTheme(context, mapView)
        addView(mapView)

        isClickable = true
        isFocusable = false
    }

    fun setClickRelay(listener: (() -> Unit)?) {
        clickRelay = listener
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = true

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_UP -> {
                if (isClickable) {
                    clickRelay?.invoke()
                    performClick()
                }
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        invalidateOutline()
        if (w > 0 && h > 0 && pendingFitPoints.isNotEmpty()) {
            fitBounds(pendingFitPoints)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        mapView.onResume()
    }

    override fun onDetachedFromWindow() {
        mapView.onPause()
        super.onDetachedFromWindow()
    }

    fun onHostResume() {
        if (isAttachedToWindow) mapView.onResume()
    }

    fun onHostPause() {
        mapView.onPause()
    }

    /**
     * @param trackKey stable identity (e.g. workout id + track fingerprint) to skip redraws
     */
    fun setTrack(trackData: TrackData?, trackKey: String?) {
        if (trackData == null || trackData.points.size < 2) {
            clear()
            return
        }
        if (trackKey != null && trackKey == boundTrackKey && trackPolyline != null) {
            mapView.invalidate()
            return
        }
        boundTrackKey = trackKey
        OsmMapTiles.applyForTheme(context, mapView)

        val geoPoints = downsample(trackData.points, MAX_DRAW_POINTS).map { point ->
            GeoPoint(point.latitude, point.longitude)
        }
        if (geoPoints.size < 2) {
            clear()
            return
        }

        trackPolyline?.let { mapView.overlays.remove(it) }
        val poly = Polyline().apply {
            outlinePaint.color = Color.RED
            outlinePaint.strokeWidth = LINE_WIDTH
            setPoints(geoPoints.toMutableList())
        }
        trackPolyline = poly
        mapView.overlays.add(poly)
        pendingFitPoints = geoPoints
        if (width > 0 && height > 0) {
            fitBounds(geoPoints)
        } else {
            post {
                if (width > 0 && height > 0 && pendingFitPoints === geoPoints) {
                    fitBounds(geoPoints)
                }
            }
        }
        mapView.invalidate()
    }

    fun clear() {
        boundTrackKey = null
        pendingFitPoints = emptyList()
        trackPolyline?.let { mapView.overlays.remove(it) }
        trackPolyline = null
        mapView.invalidate()
    }

    private fun fitBounds(points: List<GeoPoint>) {
        if (points.isEmpty() || width <= 0 || height <= 0) return
        val bounds = BoundingBox.fromGeoPoints(points)
        val latSpan = max(bounds.latNorth - bounds.latSouth, 0.001)
        val lonSpan = max(bounds.lonEast - bounds.lonWest, 0.001)
        val expanded = BoundingBox(
            bounds.latNorth + latSpan * 0.15,
            bounds.lonEast + lonSpan * 0.15,
            bounds.latSouth - latSpan * 0.15,
            bounds.lonWest - lonSpan * 0.15
        )
        mapView.zoomToBoundingBox(expanded, false, BOUNDS_PADDING_PX)
        mapView.invalidate()
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
