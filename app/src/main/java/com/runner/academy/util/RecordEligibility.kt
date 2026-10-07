package com.runner.academy.util

import com.runner.academy.data.TrackData
import com.runner.academy.data.TrackPoint
import kotlin.math.abs

/**
 * Whether the time of a track is the runner's own, so its windows may be records. Applied at
 * every computation, not stored: the heuristic for older rows can be improved without a
 * migration. The user's own "exclude from records" flag is a column filtered when records are
 * read, not here.
 */
object RecordEligibility {

    /** Fewer steps than this tell nothing about a constant speed. */
    private const val MIN_UNIFORM_STEPS = 20

    /** A step whose speed is this close to the median is "the same speed". */
    private const val UNIFORM_SPEED_TOLERANCE = 0.02

    /** Timestamps are whole milliseconds: a step may be this far off the exact uniform time. */
    private const val TIMESTAMP_ROUNDING_MS = 1.0

    /**
     * False when the point times were made up: [TrackData.timeSynthetic] (GPX without `<time>`,
     * route time from the form), or, on older tracks without the flag, a constant speed over
     * every step: the signature of [RouteTimeAligner.distributeByDistance], which a real GPS
     * never has. The details screen states the reason next to the "exclude from records" switch.
     */
    fun isTrusted(track: TrackData): Boolean =
        track.timeSynthetic != true && !hasUniformSpeed(track.points)

    private fun hasUniformSpeed(points: List<TrackPoint>): Boolean {
        if (points.size <= MIN_UNIFORM_STEPS) return false
        val meters = DoubleArray(points.size - 1)
        val millis = DoubleArray(points.size - 1)
        for (i in 1 until points.size) {
            meters[i - 1] = TrackGeometry.stepDistanceMeters(points[i - 1], points[i]).toDouble()
            millis[i - 1] = (points[i].timestamp - points[i - 1].timestamp).toDouble()
        }
        val speeds = meters.indices.filter { meters[it] > 0.0 && millis[it] > 0.0 }.map { meters[it] / millis[it] }
        if (speeds.size < MIN_UNIFORM_STEPS) return false
        val median = speeds.sorted()[speeds.size / 2]
        // Compared as times, so a step of a few centimetres is not judged by its rounded millisecond
        return meters.indices.all { i ->
            val uniformMs = meters[i] / median
            abs(millis[i] - uniformMs) <= UNIFORM_SPEED_TOLERANCE * uniformMs + TIMESTAMP_ROUNDING_MS
        }
    }
}
