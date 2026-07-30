package com.runner.academy.ui.tracking

import android.content.Context
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.drawable.Drawable
import android.location.Location
import android.util.Log
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.core.content.ContextCompat
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import com.runner.academy.R
import com.runner.academy.data.TrackPoint
import com.runner.academy.data.WorkoutSession
import com.runner.academy.util.GpsFilter
import com.runner.academy.util.OsmMapConfig
import com.runner.academy.util.OsmMapTiles
import org.osmdroid.views.overlay.CopyrightOverlay

/**
 * Manages all OSMDroid map operations for the workout tracking screen.
 *
 * Responsibilities:
 * - Map initialization and lifecycle (onResume/onPause)
 * - Camera positioning and animation
 * - Route polyline drawing and updates
 * - Location marker management with custom icon
 * - Map interaction tracking (user pan/zoom detection)
 * - Auto-center on location after inactivity
 * - Map orientation based on movement direction
 */
class MapManager(
    private val context: Context,
    private val mapView: MapView,
    private val callbacks: Callbacks
) {

    companion object {
        private const val TAG = "MapManager"
        private const val AUTO_CENTER_DELAY = 5000L
        private const val DEFAULT_ZOOM_LEVEL = 16.0
        private const val TRACK_LINE_WIDTH = 8f
        private const val TRACK_LINE_COLOR = Color.RED
        private const val GAP_DASH_ON = 24f
        private const val GAP_DASH_OFF = 16f
        private const val GAP_LINE_ALPHA = 160
        private const val DIRECTION_WINDOW_SIZE = 10
    }

    interface Callbacks {
        fun onMapCenteredStateChanged(centered: Boolean)
        fun getCurrentWorkoutSession(): WorkoutSession?
    }

    private var locationOverlay: MyLocationNewOverlay? = null
    private var trackPolyline: Polyline? = null
    private val gapTrackPolylines = mutableListOf<Polyline>()

    // User interaction tracking
    private var isUserInteractingWithMap = false
    private var lastUserInteractionTime = 0L
    private var lastMapUpdateTime = 0L

    // Auto-center runnable
    private val autoCenterRunnable = Runnable {
        isUserInteractingWithMap = false
        autoCenterOnLocation()
    }

    val isUserInteracting: Boolean
        get() = isUserInteractingWithMap

    fun initialize() {
        OsmMapConfig.apply(context)
        OsmMapTiles.applyForTheme(context, mapView)
        mapView.setMultiTouchControls(true)
        mapView.setClickable(true)
        mapView.isHorizontalMapRepetitionEnabled = false
        mapView.isVerticalMapRepetitionEnabled = false
        mapView.setFlingEnabled(true)
        mapView.setScrollableAreaLimitLatitude(
            MapView.getTileSystem().maxLatitude,
            MapView.getTileSystem().minLatitude,
            0
        )
        mapView.setUseDataConnection(true)
        mapView.setKeepScreenOn(true)
        mapView.controller.setZoom(DEFAULT_ZOOM_LEVEL)

        if (mapView.overlays.none { it is CopyrightOverlay }) {
            mapView.overlays.add(CopyrightOverlay(context))
        }

        setupMapInteractionListeners()
        setupLocationOverlay()
        setupTrackPolyline()
    }

    fun onResume() {
        OsmMapTiles.applyForTheme(context, mapView)
        mapView.onResume()
    }

    fun onPause() {
        mapView.onPause()
    }

    fun onDetach() {
        cancelAutoCenter()
        clearGapPolylines()
        mapView.onDetach()
        locationOverlay = null
        trackPolyline = null
    }

    fun cleanupAutoCenter() {
        cancelAutoCenter()
    }

    private fun setupLocationOverlay() {
        locationOverlay = MyLocationNewOverlay(GpsMyLocationProvider(context), mapView).apply {
            enableMyLocation()
            enableFollowLocation()
            setDrawAccuracyEnabled(false)
        }

        setupLocationIcon()
        mapView.overlays.add(locationOverlay!!)
    }

    private fun setupLocationIcon() {
        try {
            val customIcon = ContextCompat.getDrawable(context, R.drawable.ic_location_no_arrow)
            customIcon?.let { icon ->
                val bitmap = createBitmapFromDrawable(icon)
                locationOverlay?.setPersonIcon(bitmap)
                locationOverlay?.setDirectionIcon(bitmap)
                Log.d(TAG, "Custom location icon set successfully")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set custom location icon: ${e.message}")
        }
    }

    private fun createBitmapFromDrawable(drawable: Drawable): android.graphics.Bitmap {
        val bitmap = android.graphics.Bitmap.createBitmap(
            drawable.intrinsicWidth,
            drawable.intrinsicHeight,
            android.graphics.Bitmap.Config.ARGB_8888
        )
        val canvas = android.graphics.Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }

    private fun setupTrackPolyline() {
        trackPolyline = Polyline().apply {
            outlinePaint.color = TRACK_LINE_COLOR
            outlinePaint.strokeWidth = TRACK_LINE_WIDTH
        }
        mapView.overlays.add(trackPolyline!!)
    }

    private fun setupMapInteractionListeners() {
        val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                markUserInteraction()
                return super.onScale(detector)
            }
        })

        mapView.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            when (event.action) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    markUserInteraction()
                    cancelAutoCenter()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    scheduleAutoCenter()
                }
            }
            false
        }

        mapView.addMapListener(object : MapListener {
            override fun onScroll(event: ScrollEvent?): Boolean {
                markUserInteraction()
                return false
            }

            override fun onZoom(event: ZoomEvent?): Boolean {
                markUserInteraction()
                return false
            }
        })
    }

    private fun markUserInteraction() {
        isUserInteractingWithMap = true
        lastUserInteractionTime = System.currentTimeMillis()
        callbacks.onMapCenteredStateChanged(false)
        scheduleAutoCenter()
    }

    private fun scheduleAutoCenter() {
        cancelAutoCenter()
        mapView.handler?.postDelayed(autoCenterRunnable, AUTO_CENTER_DELAY)
    }

    private fun cancelAutoCenter() {
        mapView.handler?.removeCallbacks(autoCenterRunnable)
    }

    private fun autoCenterOnLocation() {
        val session = callbacks.getCurrentWorkoutSession() ?: return
        session.currentLocation?.let { location ->
            val geoPoint = GpsFilter.createValidGeoPoint(location)
            if (geoPoint != null) {
                mapView.controller.animateTo(geoPoint, 16.0, 1000L)
                callbacks.onMapCenteredStateChanged(true)
            } else {
                Log.w(TAG, "Invalid GPS coordinates for auto center")
            }

            val bearing = getDirectionBearing(session.trackPoints)
            if (bearing >= 0) {
                try {
                    mapView.mapOrientation = -bearing
                    Log.d("AutoCenter", "Auto-centered map and updated orientation: bearing=$bearing, mapOrientation=${-bearing}")
                } catch (e: Exception) {
                    Log.e("AutoCenter", "Error updating map orientation: ${e.message}", e)
                }
            }

            lastMapUpdateTime = System.currentTimeMillis()
        }
    }

    fun centerOnCurrentLocation() {
        isUserInteractingWithMap = false
        lastUserInteractionTime = System.currentTimeMillis()
        lastMapUpdateTime = System.currentTimeMillis()

        val session = callbacks.getCurrentWorkoutSession() ?: return
        session.currentLocation?.let { location ->
            centerOnLocation(location, session.trackPoints)
        } ?: run {
            locationOverlay?.lastFix?.let { location ->
                centerOnLocation(location, emptyList())
            }
        }
    }

    private fun centerOnLocation(location: Location, trackPoints: List<GeoPoint>) {
        val geoPoint = GpsFilter.createValidGeoPoint(location)
        if (geoPoint == null) {
            Log.w(TAG, "Invalid GPS coordinates for map center")
            return
        }

        mapView.controller.animateTo(geoPoint, 16.0, 1000L)
        callbacks.onMapCenteredStateChanged(true)

        val bearing = getDirectionBearing(trackPoints)
        if (bearing >= 0) {
            try {
                mapView.mapOrientation = -bearing
                Log.d("MapCenter", "Centered map and updated orientation: bearing=$bearing, mapOrientation=${-bearing}")
            } catch (e: Exception) {
                Log.e("MapCenter", "Error updating map orientation: ${e.message}", e)
            }
        }
    }

    fun initializeCenter() {
        locationOverlay?.myLocation?.let { geoPoint ->
            if (geoPoint.latitude in -90.0..90.0 && geoPoint.longitude in -180.0..180.0) {
                mapView.controller.setCenter(geoPoint)
            } else {
                Log.w(TAG, "Invalid GPS coordinates for map center")
            }
        }
    }

    fun updateTrack(trackPoints: List<GeoPoint>, currentLocation: Location?) {
        updateTrackFromDataPoints(
            trackPoints.map {
                TrackPoint(
                    latitude = it.latitude,
                    longitude = it.longitude,
                    timestamp = 0L,
                    accuracy = null,
                    speed = null,
                    altitude = null
                )
            },
            currentLocation
        )
    }

    /**
     * Draws continuous track segments as solid lines and GPS outages as dashed connectors.
     */
    fun updateTrackFromDataPoints(trackDataPoints: List<TrackPoint>, currentLocation: Location?) {
        clearGapPolylines()

        if (trackDataPoints.isEmpty() && currentLocation == null) {
            trackPolyline?.setPoints(mutableListOf())
            mapView.invalidate()
            return
        }

        val segments = splitTrackIntoSegments(trackDataPoints)
        if (segments.isEmpty()) {
            trackPolyline?.setPoints(mutableListOf())
            currentLocation?.let { loc ->
                trackPolyline?.setPoints(mutableListOf(GeoPoint(loc.latitude, loc.longitude)))
            }
            mapView.invalidate()
            return
        }

        val first = segments.first().toMutableList()
        if (segments.size == 1 && currentLocation != null) {
            first.add(GeoPoint(currentLocation.latitude, currentLocation.longitude))
        }
        trackPolyline?.setPoints(first)

        for (i in 1 until segments.size) {
            val segment = segments[i].toMutableList()
            if (i == segments.lastIndex && currentLocation != null) {
                segment.add(GeoPoint(currentLocation.latitude, currentLocation.longitude))
            }
            val poly = Polyline().apply {
                outlinePaint.color = TRACK_LINE_COLOR
                outlinePaint.strokeWidth = TRACK_LINE_WIDTH
                setPoints(segment)
            }
            gapTrackPolylines.add(poly)
            mapView.overlays.add(poly)
        }

        // Dashed line across GPS gaps (last point of segment N → first of N+1)
        for (i in 0 until segments.lastIndex) {
            val from = segments[i].lastOrNull() ?: continue
            val to = segments[i + 1].firstOrNull() ?: continue
            val dashed = createDashedGapPolyline(from, to)
            gapTrackPolylines.add(dashed)
            mapView.overlays.add(dashed)
        }

        mapView.invalidate()
    }

    private fun createDashedGapPolyline(from: GeoPoint, to: GeoPoint): Polyline {
        return Polyline().apply {
            outlinePaint.color = TRACK_LINE_COLOR
            outlinePaint.strokeWidth = TRACK_LINE_WIDTH
            outlinePaint.alpha = GAP_LINE_ALPHA
            outlinePaint.pathEffect = DashPathEffect(floatArrayOf(GAP_DASH_ON, GAP_DASH_OFF), 0f)
            setPoints(mutableListOf(from, to))
        }
    }

    private fun splitTrackIntoSegments(points: List<TrackPoint>): List<List<GeoPoint>> {
        if (points.isEmpty()) return emptyList()
        val segments = mutableListOf<MutableList<GeoPoint>>()
        var current = mutableListOf<GeoPoint>()
        for (point in points) {
            if (point.afterGap && current.isNotEmpty()) {
                segments.add(current)
                current = mutableListOf()
            }
            current.add(GeoPoint(point.latitude, point.longitude))
        }
        if (current.isNotEmpty()) {
            segments.add(current)
        }
        return segments
    }

    private fun clearGapPolylines() {
        gapTrackPolylines.forEach { mapView.overlays.remove(it) }
        gapTrackPolylines.clear()
    }

    fun updateMapOrientation(session: WorkoutSession) {
        session.currentLocation ?: return
        val bearing = getDirectionBearing(session.trackPoints)
        if (bearing >= 0) {
            try {
                mapView.mapOrientation = -bearing
                Log.d("MapOrientation", "Updated map orientation: bearing=$bearing, mapOrientation=${-bearing}")
            } catch (e: Exception) {
                Log.e("MapOrientation", "Error updating map orientation: ${e.message}", e)
            }
        }
    }

    fun autoCenterIfNeeded(session: WorkoutSession) {
        session.currentLocation ?: return
        if (session.gpsStatus != com.runner.academy.data.GpsStatus.FOUND) return

        val location = session.currentLocation!!
        if (!isUserInteractingWithMap) {
            val mapTick = System.currentTimeMillis()
            if (mapTick - lastMapUpdateTime > 2000) {
                val geoPoint = GpsFilter.createValidGeoPoint(location)
                if (geoPoint != null) {
                    mapView.controller.animateTo(geoPoint, 16.0, 1000L)
                    callbacks.onMapCenteredStateChanged(true)
                    lastMapUpdateTime = mapTick
                } else {
                    Log.w(TAG, "Invalid GPS coordinates for map update")
                }
            }
        } else {
            scheduleAutoCenter()
        }
    }

    private fun getDirectionBearing(trackPoints: List<GeoPoint>): Float {
        if (trackPoints.size < 2) return -1f
        val windowSize = minOf(DIRECTION_WINDOW_SIZE, trackPoints.size)
        val from = trackPoints[trackPoints.size - windowSize]
        val to = trackPoints.last()
        return calculateBearing(from, to)
    }

    private fun calculateBearing(from: GeoPoint, to: GeoPoint): Float {
        val lat1 = Math.toRadians(from.latitude)
        val lat2 = Math.toRadians(to.latitude)
        val deltaLon = Math.toRadians(to.longitude - from.longitude)

        val y = Math.sin(deltaLon) * Math.cos(lat2)
        val x = Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(deltaLon)

        var bearing = Math.toDegrees(Math.atan2(y, x))
        bearing = (bearing + 360) % 360

        return bearing.toFloat()
    }

    fun getLastMapUpdateTime(): Long = lastMapUpdateTime
}
