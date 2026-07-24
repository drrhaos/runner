package com.drrhaos.runner.ui.workout

import android.graphics.Color
import android.graphics.DashPathEffect
import com.drrhaos.runner.data.TrackData
import com.drrhaos.runner.data.TrackPoint
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

/**
 * Handles map display for workout detail screen.
 * Manages route polyline drawing, start/end markers, camera bounds fitting,
 * and map initialization/lifecycle.
 */
class DetailMapManager(
    private val mapView: MapView,
    private val context: android.content.Context
) {

    companion object {
        private const val TRACK_LINE_WIDTH = 10f
        private const val TRACK_LINE_COLOR = Color.RED
        private const val GAP_DASH_ON = 24f
        private const val GAP_DASH_OFF = 16f
        private const val GAP_LINE_ALPHA = 160
    }

    private var trackPolyline: Polyline? = null
    private val gapPolylines = mutableListOf<Polyline>()
    private var positionMarker: Marker? = null
    private var positionMarkerEnd: Marker? = null

    fun initialize() {
        Configuration.getInstance().load(context, context.getSharedPreferences("osmdroid", 0))

        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)
        mapView.controller.setZoom(15.0)

        mapView.isClickable = true
        mapView.isFocusable = true

        trackPolyline = Polyline().apply {
            outlinePaint.color = TRACK_LINE_COLOR
            outlinePaint.strokeWidth = TRACK_LINE_WIDTH
        }
        mapView.overlays.add(trackPolyline)

        positionMarker = Marker(mapView).apply {
            icon = createDefaultMarkerIcon(Color.BLUE)
            setVisible(false)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            setInfoWindowAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_TOP)
        }
        mapView.overlays.add(positionMarker)

        positionMarkerEnd = Marker(mapView).apply {
            icon = createDefaultMarkerIcon(Color.BLUE)
            setVisible(false)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            setInfoWindowAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_TOP)
        }
        mapView.overlays.add(positionMarkerEnd)

        android.util.Log.d("WorkoutDetail", "Map setup completed")
    }

    fun displayTrack(trackData: TrackData) {
        if (trackData.points.isEmpty()) return

        // Remove previous route overlays except markers
        trackPolyline?.let { mapView.overlays.remove(it) }
        gapPolylines.forEach { mapView.overlays.remove(it) }
        gapPolylines.clear()

        val segments = mutableListOf<MutableList<GeoPoint>>()
        var current = mutableListOf<GeoPoint>()
        for (point in trackData.points) {
            if (point.afterGap && current.isNotEmpty()) {
                segments.add(current)
                current = mutableListOf()
            }
            current.add(GeoPoint(point.latitude, point.longitude))
        }
        if (current.isNotEmpty()) segments.add(current)

        val allGeoPoints = mutableListOf<GeoPoint>()
        segments.forEachIndexed { index, segment ->
            allGeoPoints.addAll(segment)
            val poly = Polyline().apply {
                outlinePaint.color = TRACK_LINE_COLOR
                outlinePaint.strokeWidth = TRACK_LINE_WIDTH
                setPoints(segment)
            }
            if (index == 0) {
                trackPolyline = poly
            } else {
                gapPolylines.add(poly)
            }
            mapView.overlays.add(poly)
        }

        // Dashed connectors across GPS gaps
        for (i in 0 until segments.lastIndex) {
            val from = segments[i].lastOrNull() ?: continue
            val to = segments[i + 1].firstOrNull() ?: continue
            val dashed = Polyline().apply {
                outlinePaint.color = TRACK_LINE_COLOR
                outlinePaint.strokeWidth = TRACK_LINE_WIDTH
                outlinePaint.alpha = GAP_LINE_ALPHA
                outlinePaint.pathEffect = DashPathEffect(floatArrayOf(GAP_DASH_ON, GAP_DASH_OFF), 0f)
                setPoints(mutableListOf(from, to))
            }
            gapPolylines.add(dashed)
            mapView.overlays.add(dashed)
        }

        if (allGeoPoints.isNotEmpty()) {
            val bounds = BoundingBox.fromGeoPoints(allGeoPoints)
            val latSpan = bounds.latNorth - bounds.latSouth
            val lonSpan = bounds.lonEast - bounds.lonWest
            val latPadding = latSpan * 0.1
            val lonPadding = lonSpan * 0.1
            val expandedBounds = BoundingBox(
                bounds.latNorth + latPadding,
                bounds.lonEast + lonPadding,
                bounds.latSouth - latPadding,
                bounds.lonWest - lonPadding
            )
            mapView.zoomToBoundingBox(expandedBounds, true, 100)
        }

        mapView.invalidate()
        android.util.Log.d("WorkoutDetail", "Successfully loaded track with ${allGeoPoints.size} points in ${segments.size} segments")
    }

    fun showPositionOnMap(trackPoint: TrackPoint) {
        positionMarker?.let { marker ->
            val geoPoint = GeoPoint(trackPoint.latitude, trackPoint.longitude)
            marker.position = geoPoint
            marker.setVisible(true)
            marker.isFlat = false
            mapView.invalidate()
        }
    }

    fun showSegmentOnMap(startPoint: TrackPoint, endPoint: TrackPoint) {
        showPositionOnMap(startPoint)
        showPositionOnMapEnd(endPoint)

        val midLat = (startPoint.latitude + endPoint.latitude) / 2.0
        val midLon = (startPoint.longitude + endPoint.longitude) / 2.0
        val midPoint = GeoPoint(midLat, midLon)
        mapView.controller.animateTo(midPoint)
    }

    private fun showPositionOnMapEnd(trackPoint: TrackPoint) {
        positionMarkerEnd?.let { marker ->
            val geoPoint = GeoPoint(trackPoint.latitude, trackPoint.longitude)
            marker.position = geoPoint
            marker.setVisible(true)
            marker.isFlat = false
            mapView.invalidate()
        }
    }

    fun hidePositionMarkers() {
        positionMarker?.setVisible(false)
        positionMarkerEnd?.setVisible(false)
        mapView.invalidate()
    }

    fun onResume() {
        mapView.onResume()
    }

    fun onPause() {
        mapView.onPause()
    }

    fun onDetach() {
        mapView.onDetach()
    }

    private fun createDefaultMarkerIcon(color: Int = Color.BLUE): android.graphics.drawable.Drawable {
        val size = (32 * context.resources.displayMetrics.density / 3f).toInt()
        val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = android.graphics.Paint.Style.FILL
        }
        val radius = size / 2f - 2f
        canvas.drawCircle(size / 2f, size / 2f, radius, paint)
        paint.color = Color.WHITE
        paint.style = android.graphics.Paint.Style.STROKE
        paint.strokeWidth = 2f
        canvas.drawCircle(size / 2f, size / 2f, radius, paint)
        return android.graphics.drawable.BitmapDrawable(context.resources, bitmap)
    }
}
