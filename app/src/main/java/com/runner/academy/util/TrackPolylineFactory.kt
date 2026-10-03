package com.runner.academy.util

import android.graphics.Color
import android.graphics.DashPathEffect
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Polyline
import com.runner.academy.data.TrackPoint

/**
 * Shared OSM track geometry for live tracking and workout detail maps, built from
 * [TrackRuns]: solid lines for runs of fixes, the long translucent dashes of a plain GPS
 * gap (unchanged from before bridges existed) and short opaque dashes for a bridged,
 * counted stretch.
 */
object TrackPolylineFactory {

    data class Style(
        val color: Int = Color.RED,
        val strokeWidth: Float,
        val gapDashOn: Float = 24f,
        val gapDashOff: Float = 16f,
        val gapAlpha: Int = 160,
        val bridgeDashOn: Float = BRIDGE_DASH_ON_PER_STROKE * strokeWidth,
        val bridgeDashOff: Float = BRIDGE_DASH_OFF_PER_STROKE * strokeWidth,
        val bridgeAlpha: Int = 255
    ) {
        companion object {
            val LIVE = Style(strokeWidth = 8f)
            val DETAIL = Style(strokeWidth = 10f)
        }
    }

    /**
     * Bridge dash lengths relative to the stroke width, shared with the list previews so a
     * bridge looks the same everywhere: short dashes, about one stroke width apart.
     */
    const val BRIDGE_DASH_ON_PER_STROKE = 1.25f
    const val BRIDGE_DASH_OFF_PER_STROKE = 1f

    /** Lines a renderer adds to the map, bottom to top. */
    data class Overlays(
        val solids: List<Polyline>,
        val gaps: List<Polyline>,
        val bridges: List<Polyline>
    )

    fun createSolid(points: List<GeoPoint> = emptyList(), style: Style): Polyline {
        return Polyline().apply {
            applySolidPaint(this, style)
            if (points.isNotEmpty()) {
                setPoints(points.toMutableList())
            }
        }
    }

    /** Connector across a plain GPS gap — the pre-bridge look, kept for every track. */
    fun createDashedGap(from: GeoPoint, to: GeoPoint, style: Style): Polyline =
        createDashed(from, to, style.strokeWidth, style.color, style.gapAlpha, style.gapDashOn, style.gapDashOff)

    /** Bridged (counted) stretch: opaque short dashes, unlike the translucent gap connector. */
    fun createDashedBridge(from: GeoPoint, to: GeoPoint, style: Style): Polyline =
        createDashed(from, to, style.strokeWidth, style.color, style.bridgeAlpha, style.bridgeDashOn, style.bridgeDashOff)

    private fun createDashed(
        from: GeoPoint,
        to: GeoPoint,
        strokeWidth: Float,
        color: Int,
        alpha: Int,
        dashOn: Float,
        dashOff: Float
    ): Polyline {
        return Polyline().apply {
            outlinePaint.color = color
            outlinePaint.strokeWidth = strokeWidth
            outlinePaint.alpha = alpha
            outlinePaint.pathEffect = DashPathEffect(floatArrayOf(dashOn, dashOff), 0f)
            setPoints(mutableListOf(from, to))
        }
    }

    fun applySolidPaint(polyline: Polyline, style: Style) {
        polyline.outlinePaint.color = style.color
        polyline.outlinePaint.strokeWidth = style.strokeWidth
        polyline.outlinePaint.alpha = 255
        polyline.outlinePaint.pathEffect = null
    }

    /** One solid per [TrackRun.Solid], one gap connector per [TrackRun.Gap], one bridge per [TrackRun.Bridge]. */
    fun buildOverlays(runs: List<TrackRun>, style: Style): Overlays = Overlays(
        solids = runs.solids().map { createSolid(it.points.map(::toGeoPoint), style) },
        gaps = runs.gaps().map { createDashedGap(toGeoPoint(it.from), toGeoPoint(it.to), style) },
        bridges = runs.bridges().map { createDashedBridge(toGeoPoint(it.from), toGeoPoint(it.to), style) }
    )

    fun toGeoPoint(point: TrackPoint): GeoPoint = GeoPoint(point.latitude, point.longitude)
}
