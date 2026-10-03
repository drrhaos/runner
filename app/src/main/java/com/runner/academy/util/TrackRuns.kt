package com.runner.academy.util

import com.runner.academy.data.LocationSource
import com.runner.academy.data.TrackPoint

/**
 * A drawable piece of a track: a [Solid] line, or a dashed [Bridge] over a dropped
 * (false-signal) stretch whose distance was still counted.
 */
sealed class TrackRun {
    abstract val points: List<TrackPoint>

    /** Contiguous fixes drawn as one solid line (may hold a single point). */
    data class Solid(override val points: List<TrackPoint>) : TrackRun()

    /** Straight dashed step from [from] to the bridge point [to] ([TrackGeometry.isBridgeStep]). */
    data class Bridge(val from: TrackPoint, val to: TrackPoint) : TrackRun() {
        override val points: List<TrackPoint> get() = listOf(from, to)

        /** The bridged distance came from steps rather than the straight line. */
        val fromPedometer: Boolean get() = to.source == LocationSource.PEDOMETER.name
    }
}

fun List<TrackRun>.solids(): List<TrackRun.Solid> = filterIsInstance<TrackRun.Solid>()

fun List<TrackRun>.bridges(): List<TrackRun.Bridge> = filterIsInstance<TrackRun.Bridge>()

/** Every drawn point once, in order (bridge ends are already in the solids around them). */
fun List<TrackRun>.allPoints(): List<TrackPoint> = solids().flatMap { it.points }

/**
 * Splits a track for drawing, shared by every renderer (live map, detail map, previews):
 * - plain [TrackPoint.afterGap] → a break, nothing drawn across it;
 * - a bridge step ([TrackGeometry.isBridgeStep]) → a [TrackRun.Bridge] between two solids.
 *
 * Every point lands in exactly one [TrackRun.Solid]; a bridge reuses the last point of the
 * solid before it and the first point of the solid after it.
 */
object TrackRuns {

    fun split(points: List<TrackPoint>): List<TrackRun> {
        if (points.isEmpty()) return emptyList()
        val runs = mutableListOf<TrackRun>()
        var current = mutableListOf(points.first())
        for (i in 1 until points.size) {
            val point = points[i]
            if (!point.afterGap) {
                current.add(point)
                continue
            }
            runs.add(TrackRun.Solid(current))
            if (TrackGeometry.isBridgeStep(point)) {
                runs.add(TrackRun.Bridge(from = points[i - 1], to = point))
            }
            current = mutableListOf(point)
        }
        runs.add(TrackRun.Solid(current))
        return runs
    }

    /**
     * Thins solids to about [maxPoints] in total, keeping both ends of each solid so breaks
     * and bridges stay exact. Bridges are unchanged.
     */
    fun downsample(runs: List<TrackRun>, maxPoints: Int): List<TrackRun> {
        val total = runs.solids().sumOf { it.points.size }
        if (total <= maxPoints) return runs
        return runs.map { run ->
            when (run) {
                is TrackRun.Bridge -> run
                is TrackRun.Solid -> {
                    val budget = (maxPoints.toLong() * run.points.size / total).toInt()
                    TrackRun.Solid(thin(run.points, budget.coerceAtLeast(2)))
                }
            }
        }
    }

    private fun thin(points: List<TrackPoint>, maxPoints: Int): List<TrackPoint> {
        if (points.size <= maxPoints) return points
        val step = (points.size - 1).toDouble() / (maxPoints - 1)
        return List(maxPoints) { i ->
            points[(i * step).toInt().coerceIn(0, points.lastIndex)]
        }.toMutableList().also { it[it.lastIndex] = points.last() }
    }
}
