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
    private var lastUpcomingWarnedIndex: Int = -1

    fun reset() {
        index = 0
        segmentStartElapsedMs = 0L
        segmentStartDistanceM = 0f
        lastAnnouncedIndex = -1
        lastUpcomingWarnedIndex = -1
    }

    fun currentSegment(): WorkoutTemplateSegment? = segments.getOrNull(index)

    fun peekNextSegment(): WorkoutTemplateSegment? = segments.getOrNull(index + 1)

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

    /** Returns true once when a new segment becomes current (for TTS / beep). */
    fun consumeSegmentAnnouncement(state: State): Boolean {
        if (state.segment == null) return false
        if (state.segmentIndex == lastAnnouncedIndex) return false
        lastAnnouncedIndex = state.segmentIndex
        return true
    }

    /**
     * Remaining time in the current segment.
     * Duration goals use the target clock; distance goals estimate from [paceMinPerKm].
     */
    fun estimateRemainingMs(state: State, paceMinPerKm: Float?): Long? {
        val segment = state.segment ?: return null
        if (state.completed) return 0L
        return when (segment.goalType) {
            SegmentGoalType.DURATION -> {
                val target = segment.durationMs?.takeIf { it > 0 } ?: return null
                (target - state.segmentElapsedMs).coerceAtLeast(0L)
            }
            SegmentGoalType.DISTANCE -> {
                val target = segment.distanceMeters?.takeIf { it > 0f } ?: return null
                val remainingM = (target - state.segmentDistanceMeters).coerceAtLeast(0f)
                if (remainingM <= 0f) return 0L
                val pace = paceMinPerKm?.takeIf { it > 0f }
                    ?: segment.targetPaceMinPerKm?.takeIf { it > 0f }
                    ?: return null
                (pace * 60_000f * (remainingM / 1000f)).toLong().coerceAtLeast(0L)
            }
        }
    }

    /**
     * True once per segment when ~30s remain before the next interval.
     * Skipped for segments shorter than the warning window.
     */
    fun consumeUpcomingWarning(state: State, remainingMs: Long?): Boolean {
        if (state.completed || state.segment == null) return false
        if (peekNextSegment() == null) return false
        if (remainingMs == null) return false
        if (remainingMs <= 0L || remainingMs > UPCOMING_WARNING_MS) return false
        val totalMs = remainingMs + state.segmentElapsedMs
        if (totalMs <= UPCOMING_WARNING_MS) return false
        if (state.segmentIndex == lastUpcomingWarnedIndex) return false
        lastUpcomingWarnedIndex = state.segmentIndex
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

    companion object {
        const val UPCOMING_WARNING_MS = 30_000L
    }
}
