package com.runner.academy.util

import android.graphics.Color
import android.graphics.DashPathEffect
import com.runner.academy.data.TrackPoint
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Polyline

/**
 * Shared OSM track geometry for live tracking and workout detail maps, built from
 * [TrackRuns]: solid lines for runs of fixes, dashed lines for bridged stretches,
 * nothing across plain GPS gaps.
 */
object TrackPolylineFactory {

    data class Style(
        val color: Int = Color.RED,
        val strokeWidth: Float,
        val bridgeDashOn: Float = 24f,
        val bridgeDashOff: Float = 16f,
        val bridgeAlpha: Int = 160
    ) {
        companion object {
            val LIVE = Style(strokeWidth = 8f)
            val DETAIL = Style(strokeWidth = 10f)
        }
    }

    fun createSolid(points: List<GeoPoint> = emptyList(), style: Style): Polyline {
        return Polyline().apply {
            applySolidPaint(this, style)
            if (points.isNotEmpty()) {
                setPoints(points.toMutableList())
            }
        }
    }

    fun createDashedBridge(from: GeoPoint, to: GeoPoint, style: Style): Polyline {
        return Polyline().apply {
            outlinePaint.color = style.color
            outlinePaint.strokeWidth = style.strokeWidth
            outlinePaint.alpha = style.bridgeAlpha
            outlinePaint.pathEffect = DashPathEffect(
                floatArrayOf(style.bridgeDashOn, style.bridgeDashOff),
                0f
            )
            setPoints(mutableListOf(from, to))
        }
    }

    fun applySolidPaint(polyline: Polyline, style: Style) {
        polyline.outlinePaint.color = style.color
        polyline.outlinePaint.strokeWidth = style.strokeWidth
        polyline.outlinePaint.alpha = 255
        polyline.outlinePaint.pathEffect = null
    }

    /** One solid polyline per [TrackRun.Solid] and one dashed polyline per [TrackRun.Bridge]. */
    fun buildOverlays(
        runs: List<TrackRun>,
        style: Style
    ): Pair<List<Polyline>, List<Polyline>> {
        val solids = runs.solids().map { createSolid(it.points.map(::toGeoPoint), style) }
        val bridges = runs.bridges().map {
            createDashedBridge(toGeoPoint(it.from), toGeoPoint(it.to), style)
        }
        return solids to bridges
    }

    fun toGeoPoint(point: TrackPoint): GeoPoint = GeoPoint(point.latitude, point.longitude)
}
