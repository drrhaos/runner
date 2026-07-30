package com.runner.academy.util

import com.runner.academy.data.SegmentGoalType
import com.runner.academy.data.WorkoutTemplateSegment

/**
 * Advances through template segments using elapsed workout time (excluding pause)
 * and distance in meters.
 */
class IntervalEngine(
    private val segments: List<WorkoutTemplateSegment>
) {
    data class State(
        val segmentIndex: Int,
        val segment: WorkoutTemplateSegment?,
        val segmentElapsedMs: Long,
        val segmentDistanceMeters: Float,
        val segmentProgress: Float,
        val completed: Boolean
    )

    private var index: Int = 0
    private var segmentStartElapsedMs: Long = 0L
    private var segmentStartDistanceM: Float = 0f
    private var lastAnnouncedIndex: Int = -1

    fun reset() {
        index = 0
        segmentStartElapsedMs = 0L
        segmentStartDistanceM = 0f
        lastAnnouncedIndex = -1
    }

    fun currentSegment(): WorkoutTemplateSegment? = segments.getOrNull(index)

    fun segmentCount(): Int = segments.size

    fun currentIndex(): Int = index

    fun allSegments(): List<WorkoutTemplateSegment> = segments

    /**
     * @param workoutElapsedMs total moving time of the workout
     * @param workoutDistanceMeters total distance of the workout
     */
    fun update(workoutElapsedMs: Long, workoutDistanceMeters: Float): State {
        if (segments.isEmpty()) {
            return State(
                segmentIndex = 0,
                segment = null,
                segmentElapsedMs = workoutElapsedMs,
                segmentDistanceMeters = workoutDistanceMeters,
                segmentProgress = 0f,
                completed = true
            )
        }

        while (index < segments.size) {
            val segment = segments[index]
            val elapsedInSegment = (workoutElapsedMs - segmentStartElapsedMs).coerceAtLeast(0L)
            val distanceInSegment =
                (workoutDistanceMeters - segmentStartDistanceM).coerceAtLeast(0f)
            val progress = progressOf(segment, elapsedInSegment, distanceInSegment)
            if (progress < 1f) {
                return State(
                    segmentIndex = index,
                    segment = segment,
                    segmentElapsedMs = elapsedInSegment,
                    segmentDistanceMeters = distanceInSegment,
                    segmentProgress = progress,
                    completed = false
                )
            }
            // advance
            index++
            segmentStartElapsedMs = workoutElapsedMs
            segmentStartDistanceM = workoutDistanceMeters
        }

        val last = segments.last()
        return State(
            segmentIndex = segments.lastIndex,
            segment = last,
            segmentElapsedMs = (workoutElapsedMs - segmentStartElapsedMs).coerceAtLeast(0L),
            segmentDistanceMeters = (workoutDistanceMeters - segmentStartDistanceM).coerceAtLeast(0f),
            segmentProgress = 1f,
            completed = true
        )
    }

    /** Returns true once when a new segment becomes current (for TTS). */
    fun consumeSegmentAnnouncement(state: State): Boolean {
        if (state.segment == null) return false
        if (state.segmentIndex == lastAnnouncedIndex) return false
        lastAnnouncedIndex = state.segmentIndex
        return true
    }

    private fun progressOf(
        segment: WorkoutTemplateSegment,
        elapsedMs: Long,
        distanceM: Float
    ): Float {
        return when (segment.goalType) {
            SegmentGoalType.DURATION -> {
                val target = segment.durationMs?.takeIf { it > 0 } ?: return 1f
                (elapsedMs.toFloat() / target.toFloat()).coerceIn(0f, 1f)
            }
            SegmentGoalType.DISTANCE -> {
                val target = segment.distanceMeters?.takeIf { it > 0f } ?: return 1f
                (distanceM / target).coerceIn(0f, 1f)
            }
        }
    }
}
