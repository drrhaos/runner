package com.runner.academy.util

import com.runner.academy.data.LocationSource
import com.runner.academy.data.RecordDistance
import com.runner.academy.data.TrackData
import com.runner.academy.data.TrackPoint
import kotlin.math.roundToLong

/**
 * The fastest window of a track over each record distance, by the wall clock (pauses inside
 * the window count, as in a race).
 *
 * Distance along the track is counted as the total counts it ([TrackGeometry.stepDistanceMeters]:
 * a gap adds none, a bridge adds its metres), without the lead-in and the tail, which have no
 * time of their own. Time is the point timestamps, linear within a step. The window time as a
 * function of its start is piecewise linear, with breaks where either end crosses a point, so
 * its minimum has one end on a point: two passes with two pointers ("start on a point, end
 * interpolated" and the reverse, as [TrackChartBuilder.buildSegments] interpolates a split
 * boundary) find it exactly, O(n) per distance.
 */
object BestEfforts {

    /**
     * Largest share of a window bridged by steps ([LocationSource.PEDOMETER]) for it to count;
     * above zero the record is marked approximate.
     */
    const val MAX_STEPS_SHARE = 0.10f

    /** Floating error of the share: exactly 10 % by steps still counts. */
    private const val SHARE_EPSILON = 1e-9

    /** Efforts of [track]; none when its time is not the runner's own ([RecordEligibility]). */
    fun compute(track: TrackData, distances: List<RecordDistance> = RecordDistance.entries): List<Effort> =
        if (RecordEligibility.isTrusted(track)) compute(track.points, distances) else emptyList()

    /**
     * The fastest admissible window of [points] over each of [distances] the track covers. A
     * distance with any window faster than its world record is left out whole: such a window
     * proves a GPS failure nearby, and the windows next to it share the failure.
     */
    fun compute(points: List<TrackPoint>, distances: List<RecordDistance>): List<Effort> {
        if (points.size < 2) return emptyList()
        val track = Cumulative.of(points)
        return distances.mapNotNull { fastest(track, it) }
    }

    /** Distance [d], time [t] and metres bridged by steps [s] at each point, from the first one. */
    private class Cumulative(val d: DoubleArray, val t: DoubleArray, val s: DoubleArray) {
        val size get() = d.size

        companion object {
            fun of(points: List<TrackPoint>): Cumulative {
                val n = points.size
                val d = DoubleArray(n)
                val t = DoubleArray(n)
                val s = DoubleArray(n)
                t[0] = points[0].timestamp.toDouble()
                for (i in 1 until n) {
                    val prev = points[i - 1]
                    val point = points[i]
                    val step = TrackGeometry.stepDistanceMeters(prev, point).toDouble()
                        .takeIf { it.isFinite() && it > 0.0 } ?: 0.0
                    val bySteps = TrackGeometry.isBridgeStep(point) && point.source == LocationSource.PEDOMETER.name
                    d[i] = d[i - 1] + step
                    s[i] = s[i - 1] + if (bySteps) step else 0.0
                    // Time never runs back: a step with an earlier timestamp takes no time
                    t[i] = maxOf(t[i - 1], point.timestamp.toDouble())
                }
                return Cumulative(d, t, s)
            }
        }
    }

    /** A window with both ends interpolated within their steps. */
    private class Window(val startT: Double, val endT: Double, val stepsM: Double) {
        val elapsed get() = endT - startT
    }

    private fun fastest(track: Cumulative, distance: RecordDistance): Effort? {
        val meters = distance.meters.toDouble()
        val n = track.size
        if (track.d[n - 1] < meters) return null
        var best: Window? = null
        var fastestAny = Double.MAX_VALUE

        fun consider(window: Window) {
            fastestAny = minOf(fastestAny, window.elapsed)
            if (window.stepsM / meters > MAX_STEPS_SHARE + SHARE_EPSILON) return
            if (best == null || window.elapsed < best!!.elapsed) best = window
        }

        // Start on point i; the end is the first moment the distance reaches d[i] + meters
        var j = 1
        for (i in 0 until n) {
            val target = track.d[i] + meters
            if (target > track.d[n - 1]) break
            while (track.d[j] < target) j++
            val (endT, endS) = at(track, j - 1, target)
            consider(Window(track.t[i], endT, endS - track.s[i]))
        }
        // End on point j; the start is the last moment the distance was d[j] - meters
        var i = 0
        for (k in 0 until n) {
            val target = track.d[k] - meters
            if (target < 0.0) continue
            while (track.d[i + 1] <= target) i++
            val (startT, startS) = at(track, i, target)
            consider(Window(startT, track.t[k], track.s[k] - startS))
        }

        val window = best ?: return null
        if (fastestAny < distance.worldRecordMs) return null
        val startTime = window.startT.roundToLong()
        val elapsedMs = window.elapsed.roundToLong()
        return Effort(
            distanceM = distance.meters,
            elapsedMs = elapsedMs,
            startTime = startTime,
            endTime = startTime + elapsedMs,
            stepsShare = (window.stepsM / meters).coerceIn(0.0, 1.0).toFloat()
        )
    }

    /** Time and step metres where the distance reaches [target] within the step from point [i] to i + 1. */
    private fun at(track: Cumulative, i: Int, target: Double): Pair<Double, Double> {
        val span = track.d[i + 1] - track.d[i]
        val f = if (span > 0.0) ((target - track.d[i]) / span).coerceIn(0.0, 1.0) else 0.0
        return track.t[i] + f * (track.t[i + 1] - track.t[i]) to track.s[i] + f * (track.s[i + 1] - track.s[i])
    }
}
