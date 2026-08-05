package com.runner.academy.ui.tracking

import android.content.Context
import android.graphics.Point
import android.graphics.drawable.Drawable
import android.location.Location
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.core.content.ContextCompat
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline
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
import com.runner.academy.util.TrackPolylineFactory
import org.osmdroid.views.overlay.CopyrightOverlay
import kotlin.math.hypot

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
        private val TRACK_STYLE = TrackPolylineFactory.Style.LIVE
        private const val DIRECTION_WINDOW_SIZE = 10
        private const val TIP_ANIM_MS = 280L
        private const val TIP_FRAME_MS = 33L
        /**
         * Anchor for [R.drawable.ic_location_no_arrow]: circle center in the 1024 viewport
         * (≈511.9, 574.4) — not the default osmdroid person feet hotspot.
         */
        private const val ICON_ANCHOR_X = 0.5f
        private const val ICON_ANCHOR_Y = 574.368272f / 1024f
        /** Blue disc radius in the same viewport (path radius ≈261.22). */
        private const val ICON_DISC_RADIUS_FRAC = 261.22153f / 1024f
        /** Slightly inside the disc rim so the stroke meets the visible edge. */
        private const val ICON_EDGE_INSET_FACTOR = 0.92f
    }

    interface Callbacks {
        fun onMapCenteredStateChanged(centered: Boolean)
        fun getCurrentWorkoutSession(): WorkoutSession?
        /** Pre-workout (and any) fix from the location provider — for GPS status UI. */
        fun onLocationFix(location: Location) {}
    }

    private var locationOverlay: MyLocationNewOverlay? = null
    private val sessionLocationProvider = SessionLocationProvider(context)
    private var trackPolyline: Polyline? = null
    private val gapTrackPolylines = mutableListOf<Polyline>()
    /** Solid polyline that receives the animated live tip (last committed segment). */
    private var tipHostPolyline: Polyline? = null

    /** Committed track geometry (no live tip); rebuilt only when trackDataPoints change. */
    private var committedSegments: List<List<GeoPoint>> = emptyList()
    private var lastTrackSignature: String? = null
    private var displayedTip: GeoPoint? = null
    private var tipAnimStart: GeoPoint? = null
    private var tipAnimTarget: GeoPoint? = null
    private var tipAnimStartElapsed = 0L
    private var personIconRadiusPx = 24f

    // User interaction tracking
    private var isUserInteractingWithMap = false
    private var lastUserInteractionTime = 0L
    private var lastMapUpdateTime = 0L
    /** While > now, scroll/zoom from animateTo must not be treated as user pan. */
    private var ignoreInteractionUntilElapsed = 0L
    /** Force an immediate center when GPS becomes usable after searching/lost. */
    private var lastAutoCenterGpsStatus: com.runner.academy.data.GpsStatus? = null
    private var hasCenteredOnCurrentFix = false

    // Auto-center runnable
    private val autoCenterRunnable = Runnable {
        isUserInteractingWithMap = false
        autoCenterOnLocation()
    }

    /**
     * Tip frames only run while the map is resumed. Pausing/detaching cancels the
     * runnable; osmdroid nulls [Polyline] outline in [MapView.onDetach], so we must
     * never call [Polyline.setPoints] after that.
     */
    private var tipFramesEnabled = false

    private val tipAnimRunnable = object : Runnable {
        override fun run() {
            if (!tipFramesEnabled) return
            val start = tipAnimStart
            val target = tipAnimTarget
            if (start == null || target == null) return
            val elapsed = SystemClock.elapsedRealtime() - tipAnimStartElapsed
            val t = (elapsed.toFloat() / TIP_ANIM_MS).coerceIn(0f, 1f)
            val eased = t * t * (3f - 2f * t) // smoothstep
            val lat = start.latitude + (target.latitude - start.latitude) * eased
            val lon = start.longitude + (target.longitude - start.longitude) * eased
            displayedTip = GeoPoint(lat, lon)
            applyLiveTip(displayedTip!!)
            if (t < 1f && tipFramesEnabled) {
                mapView.handler?.postDelayed(this, TIP_FRAME_MS)
            } else {
                displayedTip = target
                tipAnimStart = target
            }
        }
    }

    val isUserInteracting: Boolean
        get() = isUserInteractingWithMap

    fun initialize() {
        OsmMapConfig.apply(context)
        OsmMapTiles.applyForTheme(context, mapView)
        // Own detach via [onDetach]; default true destroys polylines when the view
        // leaves the window and races with tip animation / session updates.
        mapView.setDestroyMode(false)
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
        bringLocationOverlayToFront()
    }

    fun onResume() {
        OsmMapTiles.applyForTheme(context, mapView)
        tipFramesEnabled = true
        mapView.setUseDataConnection(true)
        mapView.onResume()
    }

    fun onPause() {
        tipFramesEnabled = false
        cancelTipAnimation()
        cancelAutoCenter()
        // Stop OSM tile fetches while tracking continues in the pocket
        mapView.setUseDataConnection(false)
        mapView.onPause()
    }

    fun onDetach() {
        tipFramesEnabled = false
        cancelAutoCenter()
        cancelTipAnimation()
        clearGapPolylines()
        tipHostPolyline = null
        sessionLocationProvider.destroy()
        mapView.onDetach()
        locationOverlay = null
        trackPolyline = null
    }

    fun cleanupAutoCenter() {
        cancelAutoCenter()
    }

    private fun setupLocationOverlay() {
        sessionLocationProvider.onLocationUpdated = { location ->
            callbacks.onLocationFix(location)
            // Center as soon as the first real fix arrives (before or during workout)
            if (!hasCenteredOnCurrentFix) {
                hasCenteredOnCurrentFix = true
                centerOnLocation(location, emptyList())
            }
        }
        val overlay = MyLocationNewOverlay(sessionLocationProvider, mapView).apply {
            enableMyLocation()
            // Camera follow is handled by MapManager auto-center to avoid jerky dual control
            disableFollowLocation()
            setDrawAccuracyEnabled(false)
        }
        locationOverlay = overlay
        setupLocationIcon()
        mapView.overlays.add(overlay)
    }

    private fun setupLocationIcon() {
        try {
            val customIcon = ContextCompat.getDrawable(context, R.drawable.ic_location_no_arrow)
            customIcon?.let { icon ->
                val bitmap = createBitmapFromDrawable(icon)
                // Disc radius in px (not half the full bitmap — triangle is above the circle)
                personIconRadiusPx = bitmap.width * ICON_DISC_RADIUS_FRAC
                locationOverlay?.apply {
                    setPersonIcon(bitmap)
                    setDirectionIcon(bitmap)
                    // Must refresh anchors after setPersonIcon — osmdroid keeps old hotspot pixels
                    setPersonAnchor(ICON_ANCHOR_X, ICON_ANCHOR_Y)
                    setDirectionAnchor(ICON_ANCHOR_X, ICON_ANCHOR_Y)
                }
                Log.d(TAG, "Custom location icon set successfully")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set custom location icon: ${e.message}")
        }
    }

    private fun createBitmapFromDrawable(drawable: Drawable): android.graphics.Bitmap {
        val width = drawable.intrinsicWidth.coerceAtLeast(1)
        val height = drawable.intrinsicHeight.coerceAtLeast(1)
        val bitmap = android.graphics.Bitmap.createBitmap(
            width,
            height,
            android.graphics.Bitmap.Config.ARGB_8888
        )
        val canvas = android.graphics.Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }

    private fun setupTrackPolyline() {
        val poly = TrackPolylineFactory.createSolid(style = TRACK_STYLE)
        trackPolyline = poly
        mapView.overlays.add(poly)
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
        if (SystemClock.elapsedRealtime() < ignoreInteractionUntilElapsed) {
            return
        }
        isUserInteractingWithMap = true
        lastUserInteractionTime = System.currentTimeMillis()
        callbacks.onMapCenteredStateChanged(false)
        scheduleAutoCenter()
    }

    private fun beginProgrammaticCameraMove(durationMs: Long = 1000L) {
        // Cover animateTo duration + a small slack so MapListener scroll events are ignored
        ignoreInteractionUntilElapsed = SystemClock.elapsedRealtime() + durationMs + 150L
        isUserInteractingWithMap = false
        cancelAutoCenter()
    }

    private fun scheduleAutoCenter() {
        cancelAutoCenter()
        mapView.handler?.postDelayed(autoCenterRunnable, AUTO_CENTER_DELAY)
    }

    private fun cancelAutoCenter() {
        mapView.handler?.removeCallbacks(autoCenterRunnable)
    }

    private fun autoCenterOnLocation() {
        val session = callbacks.getCurrentWorkoutSession()
        val location = session?.currentLocation
            ?: locationOverlay?.lastFix
            ?: sessionLocationProvider.getLastKnownLocation()
            ?: return
        centerOnLocation(location, session?.trackPoints.orEmpty())
    }

    fun centerOnCurrentLocation() {
        isUserInteractingWithMap = false
        lastUserInteractionTime = System.currentTimeMillis()
        lastMapUpdateTime = System.currentTimeMillis()
        hasCenteredOnCurrentFix = true

        val session = callbacks.getCurrentWorkoutSession()
        val location = session?.currentLocation
            ?: locationOverlay?.lastFix
            ?: sessionLocationProvider.getLastKnownLocation()
            ?: return
        centerOnLocation(location, session?.trackPoints.orEmpty())
    }

    private fun centerOnLocation(location: Location, trackPoints: List<GeoPoint>) {
        if (mapView.width <= 0 || mapView.height <= 0) {
            mapView.post { centerOnLocation(location, trackPoints) }
            return
        }

        val geoPoint = GpsFilter.createValidGeoPoint(location)
        if (geoPoint == null) {
            Log.w(TAG, "Invalid GPS coordinates for map center")
            return
        }

        beginProgrammaticCameraMove(1000L)
        mapView.controller.animateTo(geoPoint, 16.0, 1000L)
        callbacks.onMapCenteredStateChanged(true)
        lastMapUpdateTime = System.currentTimeMillis()

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
                beginProgrammaticCameraMove(0L)
                mapView.controller.setCenter(geoPoint)
                hasCenteredOnCurrentFix = true
                callbacks.onMapCenteredStateChanged(true)
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
     * Draws committed track segments (solid / dashed across gaps) and a live tip that
     * smoothly stretches to the person-icon rim at [currentLocation].
     */
    fun updateTrackFromDataPoints(trackDataPoints: List<TrackPoint>, currentLocation: Location?) {
        currentLocation?.let { sessionLocationProvider.publish(it) }

        val signature = trackSignature(trackDataPoints)
        if (signature != lastTrackSignature) {
            lastTrackSignature = signature
            committedSegments = splitTrackIntoSegments(trackDataPoints)
            rebuildCommittedPolylines()
        }

        // Tip animation only while map is resumed (avoids setPoints after osmdroid detach).
        if (!tipFramesEnabled) return

        if (currentLocation == null) {
            cancelTipAnimation()
            displayedTip = null
            tipAnimTarget = null
            applyLiveTip(null)
            return
        }

        val markerCenter = GeoPoint(currentLocation.latitude, currentLocation.longitude)
        val lastCommitted = committedSegments.lastOrNull()?.lastOrNull()
        val tipTarget = if (lastCommitted != null) {
            tipAtIconEdge(lastCommitted, markerCenter)
        } else {
            markerCenter
        }
        animateTipToward(tipTarget)
    }

    private fun trackSignature(points: List<TrackPoint>): String {
        if (points.isEmpty()) return "0"
        val last = points.last()
        return "${points.size}:${last.timestamp}:${last.latitude}:${last.longitude}:${last.afterGap}"
    }

    private fun rebuildCommittedPolylines() {
        cancelTipAnimation()
        clearGapPolylines()
        tipHostPolyline = trackPolyline

        if (committedSegments.isEmpty()) {
            trackPolyline?.setPoints(mutableListOf())
            tipHostPolyline = trackPolyline
            mapView.invalidate()
            return
        }

        trackPolyline?.setPoints(committedSegments.first().toMutableList())
        tipHostPolyline = trackPolyline

        for (i in 1 until committedSegments.size) {
            val poly = TrackPolylineFactory.createSolid(
                points = committedSegments[i],
                style = TRACK_STYLE
            )
            tipHostPolyline = poly
            gapTrackPolylines.add(poly)
            mapView.overlays.add(poly)
        }

        for (i in 0 until committedSegments.lastIndex) {
            val from = committedSegments[i].lastOrNull() ?: continue
            val to = committedSegments[i + 1].firstOrNull() ?: continue
            val dashed = TrackPolylineFactory.createDashedGap(from, to, TRACK_STYLE)
            gapTrackPolylines.add(dashed)
            mapView.overlays.add(dashed)
        }

        bringLocationOverlayToFront()
        mapView.invalidate()
    }

    private fun bringLocationOverlayToFront() {
        val overlay = locationOverlay ?: return
        mapView.overlays.remove(overlay)
        mapView.overlays.add(overlay)
    }

    private fun animateTipToward(target: GeoPoint) {
        if (!tipFramesEnabled) return
        val current = displayedTip
        if (current == null) {
            displayedTip = target
            tipAnimStart = target
            tipAnimTarget = target
            applyLiveTip(target)
            return
        }
        val same =
            kotlin.math.abs(current.latitude - target.latitude) < 1e-8 &&
                kotlin.math.abs(current.longitude - target.longitude) < 1e-8
        if (same && tipAnimTarget == target) return

        tipAnimStart = current
        tipAnimTarget = target
        tipAnimStartElapsed = SystemClock.elapsedRealtime()
        mapView.handler?.removeCallbacks(tipAnimRunnable)
        mapView.handler?.post(tipAnimRunnable)
    }

    private fun cancelTipAnimation() {
        mapView.handler?.removeCallbacks(tipAnimRunnable)
    }

    /**
     * Appends / replaces the live tip on the active (last) solid segment so the line
     * follows the runner without rebuilding the whole overlay stack.
     */
    private fun applyLiveTip(tip: GeoPoint?) {
        if (!tipFramesEnabled) return
        val host = tipHostPolyline ?: trackPolyline ?: return
        // After MapView.onDetach, osmdroid nulls LinearRing and clears overlays.
        if (!mapView.overlays.contains(host)) return

        val base = committedSegments.lastOrNull()?.toMutableList()
            ?: mutableListOf()
        if (tip != null) {
            val last = base.lastOrNull()
            if (last == null ||
                kotlin.math.abs(last.latitude - tip.latitude) > 1e-8 ||
                kotlin.math.abs(last.longitude - tip.longitude) > 1e-8
            ) {
                base.add(tip)
            }
        }
        host.setPoints(base)
        mapView.invalidate()
    }

    /**
     * Shortens the tip so the stroke meets the person-icon edge instead of crossing its center.
     */
    private fun tipAtIconEdge(from: GeoPoint, markerCenter: GeoPoint): GeoPoint {
        val projection = mapView.projection ?: return markerCenter
        val startPx = Point()
        val endPx = Point()
        projection.toPixels(from, startPx)
        projection.toPixels(markerCenter, endPx)
        val dx = (endPx.x - startPx.x).toFloat()
        val dy = (endPx.y - startPx.y).toFloat()
        val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()
        val inset = personIconRadiusPx * ICON_EDGE_INSET_FACTOR
        if (dist <= inset + 1f) {
            // Runner is still on the last committed point — keep tip at committed point
            return from
        }
        val ratio = (dist - inset) / dist
        val edgeX = startPx.x + dx * ratio
        val edgeY = startPx.y + dy * ratio
        return projection.fromPixels(edgeX.toInt(), edgeY.toInt()) as GeoPoint
    }

    private fun splitTrackIntoSegments(points: List<TrackPoint>): List<List<GeoPoint>> {
        return TrackPolylineFactory.splitIntoSegments(points)
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
        val tracking = session.isTracking || session.isPaused

        // Idle screen: session GPS is still the default SEARCHING and has no location.
        // Use the overlay/fallback fix and never clear the first-center flag.
        if (!tracking) {
            val location = session.currentLocation
                ?: locationOverlay?.lastFix
                ?: sessionLocationProvider.getLastKnownLocation()
                ?: return
            if (!hasCenteredOnCurrentFix) {
                hasCenteredOnCurrentFix = true
                centerOnLocation(location, emptyList())
            }
            return
        }

        val location = session.currentLocation ?: return
        if (!isGpsUsableForCenter(session.gpsStatus)) {
            // Searching / lost again — allow a fresh snap when signal returns
            if (session.gpsStatus == com.runner.academy.data.GpsStatus.SEARCHING ||
                session.gpsStatus == com.runner.academy.data.GpsStatus.LOST ||
                session.gpsStatus == com.runner.academy.data.GpsStatus.DENIED
            ) {
                hasCenteredOnCurrentFix = false
            }
            lastAutoCenterGpsStatus = session.gpsStatus
            return
        }

        val acquiredFix =
            !hasCenteredOnCurrentFix ||
                (lastAutoCenterGpsStatus != null &&
                    lastAutoCenterGpsStatus != com.runner.academy.data.GpsStatus.FOUND &&
                    session.gpsStatus == com.runner.academy.data.GpsStatus.FOUND)

        lastAutoCenterGpsStatus = session.gpsStatus

        if (acquiredFix) {
            hasCenteredOnCurrentFix = true
            centerOnLocation(location, session.trackPoints)
            return
        }

        if (!isUserInteractingWithMap) {
            val mapTick = System.currentTimeMillis()
            if (mapTick - lastMapUpdateTime > 2000) {
                centerOnLocation(location, session.trackPoints)
            }
        } else {
            scheduleAutoCenter()
        }
    }

    private fun isGpsUsableForCenter(status: com.runner.academy.data.GpsStatus): Boolean {
        return when (status) {
            com.runner.academy.data.GpsStatus.FOUND,
            com.runner.academy.data.GpsStatus.STRONG,
            com.runner.academy.data.GpsStatus.MEDIUM,
            com.runner.academy.data.GpsStatus.WEAK -> true
            else -> false
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
}
