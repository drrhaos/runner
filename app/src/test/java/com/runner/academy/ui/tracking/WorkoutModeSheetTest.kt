package com.runner.academy.ui.tracking

import com.runner.academy.R
import com.runner.academy.data.ScheduledWorkout
import com.runner.academy.data.ScheduledWorkoutWithTemplate
import com.runner.academy.data.SegmentGoalType
import com.runner.academy.data.SegmentKind
import com.runner.academy.data.TrainingIcon
import com.runner.academy.data.WorkoutTemplate
import com.runner.academy.data.WorkoutTemplateSegment
import com.runner.academy.data.WorkoutTemplateWithSegments
import com.runner.academy.data.WorkoutType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkoutModeSheetTest {

    private fun template(
        id: Long,
        iconKey: String = TrainingIcon.INTERVAL.name,
        type: WorkoutType = WorkoutType.INTERVAL_TRAINING,
        segments: List<WorkoutTemplateSegment> = emptyList()
    ) = TrackingWorkoutMode.Template(
        WorkoutTemplateWithSegments(WorkoutTemplate(id = id, name = "T$id", workoutType = type, iconKey = iconKey), segments)
    )

    private val plan = TrackingWorkoutMode.PlanToday(
        ScheduledWorkoutWithTemplate(
            ScheduledWorkout(id = 1, scheduleId = 1, dateMillis = 0, dayIndex = 11, templateId = 3),
            WorkoutTemplate(id = 3, name = "Tempo 5 km"),
            emptyList()
        )
    )

    private var nextId = 1L
    private fun segment(kind: SegmentKind, durationMs: Long? = null, meters: Float? = null, pace: Float? = null) =
        WorkoutTemplateSegment(
            id = nextId++, templateId = 1, sortOrder = nextId.toInt(), kind = kind, title = "",
            goalType = if (meters != null) SegmentGoalType.DISTANCE else SegmentGoalType.DURATION,
            durationMs = durationMs, distanceMeters = meters, targetPaceMinPerKm = pace
        )

    // --- Groups ---

    @Test
    fun `the plan comes first, then the free run, then the templates`() {
        val t1 = template(1)
        val t2 = template(2)

        val groups = WorkoutModeSheet.groups(listOf(TrackingWorkoutMode.EasyRun, plan, t1, t2))

        assertEquals(
            listOf(R.string.tracking_mode_group_plan, R.string.tracking_free_run, R.string.tracking_mode_group_templates),
            groups.map { it.title }
        )
        assertEquals(listOf(plan), groups[0].modes)
        assertEquals(listOf(TrackingWorkoutMode.EasyRun), groups[1].modes)
        assertEquals(listOf(t1, t2), groups[2].modes)
    }

    @Test
    fun `an empty group is left out`() {
        val groups = WorkoutModeSheet.groups(listOf(TrackingWorkoutMode.EasyRun))

        assertEquals(listOf(R.string.tracking_free_run), groups.map { it.title })
    }

    // --- Icons ---

    @Test
    fun `the easy run and the plan have their own icons, a template its chosen one`() {
        assertEquals(TrainingIcon.EASY, WorkoutModeSheet.icon(TrackingWorkoutMode.EasyRun))
        assertEquals(TrainingIcon.PLAN, WorkoutModeSheet.icon(plan))
        assertEquals(TrainingIcon.TEMPO, WorkoutModeSheet.icon(template(1, iconKey = TrainingIcon.TEMPO.name)))
    }

    @Test
    fun `a template with an unknown icon falls back to its workout type`() {
        assertEquals(TrainingIcon.LONG, WorkoutModeSheet.icon(template(1, iconKey = "GONE", type = WorkoutType.LONG_RUN)))
    }

    // --- Template composition ---

    @Test
    fun `repeated work of one length reads as count times length, with the total time`() {
        val segments = listOf(segment(SegmentKind.WARMUP, durationMs = 600_000)) +
            (1..6).flatMap {
                listOf(segment(SegmentKind.WORK, durationMs = 180_000), segment(SegmentKind.RECOVERY, durationMs = 120_000))
            }

        val c = TemplateComposition.of(segments)

        assertEquals(6, c.repeats)
        assertEquals(TemplateComposition.Goal.Duration(180_000), c.goal)
        assertEquals(600_000L + 6 * 300_000L, c.totalMs)
        assertFalse(c.estimated)
    }

    @Test
    fun `distance work is timed at its target pace, so the total is an estimate`() {
        val segments = (1..6).map { segment(SegmentKind.WORK, meters = 400f, pace = 4f) } +
            segment(SegmentKind.COOLDOWN, durationMs = 300_000)

        val c = TemplateComposition.of(segments)

        assertEquals(TemplateComposition.Goal.Distance(400f), c.goal)
        assertEquals(6 * 96_000L + 300_000L, c.totalMs)
        assertTrue(c.estimated)
    }

    @Test
    fun `a distance without a target pace leaves the total out`() {
        val c = TemplateComposition.of(listOf(segment(SegmentKind.WORK, meters = 5000f)))

        assertEquals(1, c.repeats)
        assertEquals(TemplateComposition.Goal.Distance(5000f), c.goal)
        assertNull(c.totalMs)
    }

    @Test
    fun `work of different lengths is counted as segments`() {
        val segments = listOf(
            segment(SegmentKind.WORK, durationMs = 60_000),
            segment(SegmentKind.WORK, durationMs = 120_000),
            segment(SegmentKind.COOLDOWN, durationMs = 300_000)
        )

        val c = TemplateComposition.of(segments)

        assertNull(c.goal)
        assertEquals(3, c.repeats)
    }

    @Test
    fun `without work segments every segment counts`() {
        val c = TemplateComposition.of(listOf(segment(SegmentKind.CUSTOM, durationMs = 5_400_000)))

        assertEquals(1, c.repeats)
        assertEquals(TemplateComposition.Goal.Duration(5_400_000), c.goal)
        assertEquals(5_400_000L, c.totalMs)
    }
}
