package com.runner.academy.util

import android.graphics.Color
import android.graphics.DashPathEffect
import com.runner.academy.data.TrackPoint
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Polyline

/**
 * Shared OSM track geometry for live tracking and workout detail maps:
 * solid segments split on [TrackPoint.afterGap], dashed connectors across gaps.
 */
object TrackPolylineFactory {

    data class Style(
        val color: Int = Color.RED,
        val strokeWidth: Float,
        val gapDashOn: Float = 24f,
        val gapDashOff: Float = 16f,
        val gapAlpha: Int = 160
    ) {
        companion object {
            val LIVE = Style(strokeWidth = 8f)
            val DETAIL = Style(strokeWidth = 10f)
        }
    }

    fun splitIntoSegments(points: List<TrackPoint>): List<List<GeoPoint>> {
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

    fun createSolid(points: List<GeoPoint> = emptyList(), style: Style): Polyline {
        return Polyline().apply {
            applySolidPaint(this, style)
            if (points.isNotEmpty()) {
                setPoints(points.toMutableList())
            }
        }
    }

    fun createDashedGap(from: GeoPoint, to: GeoPoint, style: Style): Polyline {
        return Polyline().apply {
            outlinePaint.color = style.color
            outlinePaint.strokeWidth = style.strokeWidth
            outlinePaint.alpha = style.gapAlpha
            outlinePaint.pathEffect = DashPathEffect(
                floatArrayOf(style.gapDashOn, style.gapDashOff),
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

    /**
     * One solid polyline per contiguous segment, plus a dashed connector between
     * each consecutive pair of segments.
     */
    fun buildOverlays(
        segments: List<List<GeoPoint>>,
        style: Style
    ): Pair<List<Polyline>, List<Polyline>> {
        if (segments.isEmpty()) return emptyList<Polyline>() to emptyList()

        val solids = segments.map { createSolid(it, style) }
        val gaps = mutableListOf<Polyline>()
        for (i in 0 until segments.lastIndex) {
            val from = segments[i].lastOrNull() ?: continue
            val to = segments[i + 1].firstOrNull() ?: continue
            gaps.add(createDashedGap(from, to, style))
        }
        return solids to gaps
    }
}
