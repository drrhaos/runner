package com.runner.academy.util

import com.runner.academy.data.SegmentGoalType
import com.runner.academy.data.SegmentKind
import com.runner.academy.data.TrainingIcon
import com.runner.academy.data.TrainingPlan
import com.runner.academy.data.WorkoutTemplate
import com.runner.academy.data.WorkoutTemplateSegment
import com.runner.academy.data.WorkoutType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrainingPlanBackupFormatTest {

    @Test
    fun roundTrip_preservesTemplatesPlansAndDayLinks() {
        val templates = listOf(
            TrainingPlanBackupFormat.WorkoutTemplateWithSegmentsExport(
                template = WorkoutTemplate(
                    id = 10,
                    name = "Intervals",
                    workoutType = WorkoutType.INTERVAL_TRAINING,
                    iconKey = TrainingIcon.INTERVAL.name
                ),
                segments = listOf(
                    WorkoutTemplateSegment(
                        templateId = 10,
                        sortOrder = 0,
                        kind = SegmentKind.WARMUP,
                        title = "WU",
                        goalType = SegmentGoalType.DURATION,
                        durationMs = 600_000L
                    ),
                    WorkoutTemplateSegment(
                        templateId = 10,
                        sortOrder = 1,
                        kind = SegmentKind.WORK,
                        title = "Fast",
                        goalType = SegmentGoalType.DISTANCE,
                        distanceMeters = 200f,
                        targetPaceMinPerKm = 4.5f
                    )
                )
            )
        )
        val plans = listOf(
            TrainingPlanBackupFormat.PlanWithDaysExport(
                plan = TrainingPlan(
                    id = 5,
                    name = "8 weeks",
                    durationDays = 7,
                    iconKey = TrainingIcon.PLAN.name
                ),
                days = listOf(
                    TrainingPlanBackupFormat.PlanDayDto(dayIndex = 0, templateId = 10),
                    TrainingPlanBackupFormat.PlanDayDto(dayIndex = 1, templateId = null)
                )
            )
        )

        val json = TrainingPlanBackupFormat.toBackupJson(templates, plans, "com.runner.academy")
        val parsed = TrainingPlanBackupFormat.parseBackupJson(json)

        assertEquals(1, parsed.templates.size)
        assertEquals(1, parsed.plans.size)
        val (exportTemplateId, templateExport) = parsed.templates.first()
        assertEquals(10L, exportTemplateId)
        assertEquals("Intervals", templateExport.template.name)
        assertEquals(2, templateExport.segments.size)
        assertEquals(SegmentGoalType.DISTANCE, templateExport.segments[1].goalType)
        assertEquals(200f, templateExport.segments[1].distanceMeters)

        val (_, planExport) = parsed.plans.first()
        assertEquals("8 weeks", planExport.plan.name)
        assertEquals(2, planExport.days.size)
        assertEquals(10L, planExport.days[0].templateId)
        assertEquals(null, planExport.days[1].templateId)
        assertTrue(json.contains("\"formatVersion\":1"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun parse_emptyBackup_throws() {
        TrainingPlanBackupFormat.parseBackupJson(
            """{"formatVersion":1,"templates":[],"plans":[]}"""
        )
    }
}
