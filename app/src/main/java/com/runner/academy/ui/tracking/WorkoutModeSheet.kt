package com.runner.academy.ui.tracking

import androidx.annotation.StringRes
import com.runner.academy.R
import com.runner.academy.data.SegmentGoalType
import com.runner.academy.data.SegmentKind
import com.runner.academy.data.TrainingIcon
import com.runner.academy.data.WorkoutTemplateSegment
import com.runner.academy.data.defaultTrainingIcon
import com.runner.academy.data.parseTrainingIcon
import kotlin.math.roundToLong

/** A titled group of the workout mode sheet. */
data class ModeSheetGroup(@StringRes val title: Int, val modes: List<TrackingWorkoutMode>)

/** What the workout mode sheet lists: groups and icons of the modes. */
object WorkoutModeSheet {

    /** Today's plan, the free run, the templates; an empty group is left out. */
    fun groups(options: List<TrackingWorkoutMode>): List<ModeSheetGroup> = listOf(
        ModeSheetGroup(R.string.tracking_mode_group_plan, options.filterIsInstance<TrackingWorkoutMode.PlanToday>()),
        ModeSheetGroup(R.string.tracking_free_run, options.filter { it == TrackingWorkoutMode.EasyRun }),
        ModeSheetGroup(R.string.tracking_mode_group_templates, options.filterIsInstance<TrackingWorkoutMode.Template>())
    ).filter { it.modes.isNotEmpty() }

    /** A template shows the icon it was given, as in the template list. */
    fun icon(mode: TrackingWorkoutMode): TrainingIcon = when (mode) {
        TrackingWorkoutMode.EasyRun -> TrainingIcon.EASY
        is TrackingWorkoutMode.PlanToday -> TrainingIcon.PLAN
        is TrackingWorkoutMode.Template ->
            parseTrainingIcon(mode.data.template.iconKey, mode.data.template.workoutType.defaultTrainingIcon())
    }
}

/**
 * A template in a few words: "6 × 400 m · ≈ 38 min". The work segments (all segments when there
 * are none) give the count and, when they are all alike, their [goal]. [totalMs] is null when a
 * distance has no target pace to time it; [estimated] when a distance was timed at its pace.
 */
data class TemplateComposition(
    val repeats: Int,
    val goal: Goal?,
    val totalMs: Long?,
    val estimated: Boolean
) {
    sealed class Goal {
        data class Duration(val ms: Long) : Goal()
        data class Distance(val meters: Float) : Goal()
    }

    companion object {
        fun of(segments: List<WorkoutTemplateSegment>): TemplateComposition {
            val work = segments.filter { it.kind == SegmentKind.WORK }.ifEmpty { segments }
            val goals = work.map { goalOf(it) }.distinct()
            val goal = goals.singleOrNull()
            var estimated = false
            var total: Long? = 0L
            for (segment in segments) {
                val ms = when (segment.goalType) {
                    SegmentGoalType.DURATION -> segment.durationMs
                    SegmentGoalType.DISTANCE -> {
                        val meters = segment.distanceMeters
                        val pace = segment.targetPaceMinPerKm
                        if (meters != null && pace != null) {
                            estimated = true
                            (meters / 1000f * pace * 60_000f).roundToLong()
                        } else {
                            null
                        }
                    }
                }
                total = if (ms == null) null else total?.plus(ms)
            }
            return TemplateComposition(
                repeats = if (goal != null) work.size else segments.size,
                goal = goal,
                totalMs = total,
                estimated = estimated
            )
        }

        private fun goalOf(segment: WorkoutTemplateSegment): Goal? = when (segment.goalType) {
            SegmentGoalType.DURATION -> segment.durationMs?.let { Goal.Duration(it) }
            SegmentGoalType.DISTANCE -> segment.distanceMeters?.let { Goal.Distance(it) }
        }
    }
}
