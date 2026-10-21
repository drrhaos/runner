package com.runner.academy.util

import com.runner.academy.data.SegmentGoalType
import com.runner.academy.data.SegmentKind
import com.runner.academy.data.SegmentTarget
import com.runner.academy.data.TrainingIcon
import com.runner.academy.data.TrainingPlan
import com.runner.academy.data.WorkoutTemplate
import com.runner.academy.data.WorkoutTemplateSegment
import com.runner.academy.data.WorkoutType
import com.runner.academy.data.target
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

    @Test
    fun roundTrip_keepsZoneTarget_formatStaysVersion1() {
        val zone = WorkoutTemplateSegment(
            templateId = 10, sortOrder = 0, kind = SegmentKind.WORK, title = "Tempo",
            goalType = SegmentGoalType.DURATION, durationMs = 1_200_000L, targetHrZone = 4
        )
        val templates = listOf(
            TrainingPlanBackupFormat.WorkoutTemplateWithSegmentsExport(
                template = WorkoutTemplate(id = 10, name = "Tempo"),
                segments = listOf(zone)
            )
        )

        val json = TrainingPlanBackupFormat.toBackupJson(templates, emptyList(), "com.runner.academy")
        val segment = TrainingPlanBackupFormat.parseBackupJson(json).templates.single().second.segments.single()

        assertEquals(4, segment.targetHrZone)
        assertEquals(null, segment.targetPaceMinPerKm)
        assertEquals(SegmentTarget.HrZone(4), segment.target)
        assertTrue(json.contains("\"targetHrZone\":4"))
        assertTrue(json.contains("\"formatVersion\":1"))
    }

    @Test
    fun parse_bothPaceAndZone_zoneWins() {
        val json = """{"formatVersion":1,"templates":[{"id":1,"name":"T","segments":[
            {"sortOrder":0,"kind":"WORK","title":"W","goalType":"DURATION","durationMs":60000,"targetPaceMinPerKm":4.5,"targetHrZone":2}
        ]}],"plans":[]}"""

        val segment = TrainingPlanBackupFormat.parseBackupJson(json).templates.single().second.segments.single()

        assertEquals(SegmentTarget.HrZone(2), segment.target)
        assertEquals(null, segment.targetPaceMinPerKm)
    }

    @Test
    fun parse_zoneOutOfRange_dropped() {
        val json = """{"formatVersion":1,"templates":[{"id":1,"name":"T","segments":[
            {"sortOrder":0,"kind":"WORK","title":"W","goalType":"DURATION","durationMs":60000,"targetHrZone":0}
        ]}],"plans":[]}"""

        val segment = TrainingPlanBackupFormat.parseBackupJson(json).templates.single().second.segments.single()

        assertEquals(null, segment.targetHrZone)
        assertEquals(SegmentTarget.None, segment.target)
    }

    /**
     * A backup as release 3 wrote it (no zone key) imports unchanged. Do not edit the fixture;
     * expectations are spelled out in full so a reader that drops a field fails here.
     */
    @Test
    fun goldenRelease3Backup_importsUnchanged() {
        val json = requireNotNull(javaClass.classLoader?.getResource("backups/training-plans-v1-r3.json")).readText()

        val parsed = TrainingPlanBackupFormat.parseBackupJson(json)

        assertEquals(listOf(3L, 4L), parsed.templates.map { it.first })
        val (_, intervals) = parsed.templates[0]
        assertEquals(
            WorkoutTemplate(
                name = "6 × 400 м",
                workoutType = WorkoutType.INTERVAL_TRAINING,
                iconKey = TrainingIcon.INTERVAL.name,
                notes = "По стадиону",
                createdAt = 1_758_000_000_000L,
                updatedAt = 1_758_100_000_000L
            ),
            intervals.template
        )
        assertEquals(
            listOf(
                WorkoutTemplateSegment(templateId = 0, sortOrder = 0, kind = SegmentKind.WARMUP, title = "", goalType = SegmentGoalType.DURATION, durationMs = 600_000L),
                WorkoutTemplateSegment(templateId = 0, sortOrder = 1, kind = SegmentKind.WORK, title = "Быстро", goalType = SegmentGoalType.DISTANCE, distanceMeters = 400f, targetPaceMinPerKm = 4.5f),
                WorkoutTemplateSegment(templateId = 0, sortOrder = 2, kind = SegmentKind.RECOVERY, title = "", goalType = SegmentGoalType.DURATION, durationMs = 90_000L, targetPaceMinPerKm = 7f),
                WorkoutTemplateSegment(templateId = 0, sortOrder = 3, kind = SegmentKind.COOLDOWN, title = "", goalType = SegmentGoalType.DURATION, durationMs = 300_000L)
            ),
            intervals.segments
        )
        assertEquals(
            listOf(SegmentTarget.None, SegmentTarget.Pace(4.5f), SegmentTarget.Pace(7f), SegmentTarget.None),
            intervals.segments.map { it.target }
        )
        val (_, easy) = parsed.templates[1]
        assertEquals(WorkoutType.EASY_RUN, easy.template.workoutType)
        assertEquals(null, easy.template.notes)
        assertEquals(
            listOf(WorkoutTemplateSegment(templateId = 0, sortOrder = 0, kind = SegmentKind.WORK, title = "", goalType = SegmentGoalType.DISTANCE, distanceMeters = 5000f)),
            easy.segments
        )
        val (planId, plan) = parsed.plans.single()
        assertEquals(2L, planId)
        assertEquals("Неделя", plan.plan.name)
        assertEquals(3, plan.plan.durationDays)
        assertEquals(
            listOf(
                TrainingPlanBackupFormat.PlanDayDto(0, 3L),
                TrainingPlanBackupFormat.PlanDayDto(1, null),
                TrainingPlanBackupFormat.PlanDayDto(2, 4L)
            ),
            plan.days
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun parse_emptyBackup_throws() {
        TrainingPlanBackupFormat.parseBackupJson(
            """{"formatVersion":1,"templates":[],"plans":[]}"""
        )
    }
}
