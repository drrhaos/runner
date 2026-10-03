package com.runner.academy.ui.workout

import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import com.runner.academy.data.TrackPoint
import com.runner.academy.util.TrackRun

/** Canvas paths for [TrackRun]s, shared by the list previews (no MapView there). */
internal object TrackRunPaths {

    /** Same on/off ratio and alpha as the map's dashed bridges, scaled to the stroke. */
    fun bridgePaint(trackPaint: Paint): Paint = Paint(trackPaint).apply {
        val unit = trackPaint.strokeWidth
        pathEffect = DashPathEffect(floatArrayOf(unit * 3f, unit * 2f), 0f)
        strokeCap = Paint.Cap.BUTT
        alpha = 160
    }

    /**
     * Adds each solid run to [solid] and each bridge to [bridge]; plain gaps stay empty.
     * Returns the first and last projected points, or null for an empty track.
     */
    fun build(
        runs: List<TrackRun>,
        solid: Path,
        bridge: Path,
        x: (TrackPoint) -> Float,
        y: (TrackPoint) -> Float
    ): Pair<FloatArray, FloatArray>? {
        var first: FloatArray? = null
        var last: FloatArray? = null
        for (run in runs) {
            val target = if (run is TrackRun.Bridge) bridge else solid
            run.points.forEachIndexed { index, point ->
                val px = x(point)
                val py = y(point)
                if (index == 0) target.moveTo(px, py) else target.lineTo(px, py)
                if (run is TrackRun.Solid) {
                    if (first == null) first = floatArrayOf(px, py)
                    last = floatArrayOf(px, py)
                }
            }
        }
        val start = first ?: return null
        return start to (last ?: start)
    }
}
