package com.runner.academy.ui.tracking

import com.runner.academy.data.ScheduledWorkoutWithTemplate
import com.runner.academy.data.WorkoutTemplateSegment
import com.runner.academy.data.WorkoutTemplateWithSegments
import com.runner.academy.data.WorkoutType

/** Selection in the tracking workout mode spinner. */
sealed class TrackingWorkoutMode {
    data object EasyRun : TrackingWorkoutMode()

    data class PlanToday(
        val scheduled: ScheduledWorkoutWithTemplate
    ) : TrackingWorkoutMode()

    data class Template(
        val data: WorkoutTemplateWithSegments
    ) : TrackingWorkoutMode()

    fun workoutType(): WorkoutType = when (this) {
        EasyRun -> WorkoutType.EASY_RUN
        is PlanToday -> scheduled.template?.workoutType ?: WorkoutType.INTERVAL_TRAINING
        is Template -> data.template.workoutType
    }

    fun segments(): List<WorkoutTemplateSegment> = when (this) {
        EasyRun -> emptyList()
        is PlanToday -> scheduled.segments
        is Template -> data.segments
    }

    fun displayTitle(): String = when (this) {
        EasyRun -> ""
        is PlanToday -> scheduled.template?.name.orEmpty()
        is Template -> data.template.name
    }

    fun scheduledIdOrNull(): Long? = when (this) {
        is PlanToday -> scheduled.scheduled.id
        else -> null
    }

    fun hasIntervals(): Boolean = segments().isNotEmpty()
}
