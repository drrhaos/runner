package com.runner.academy.util

import com.runner.academy.data.SegmentGoalType
import com.runner.academy.data.SegmentKind
import com.runner.academy.data.SegmentTarget
import com.runner.academy.data.WorkoutTemplateSegment
import com.runner.academy.data.target
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** The segment snapshot of a run, checkpoint and saved workout keeps the target (architecture §06). */
class IntervalSegmentsJsonTest {

    private val zoneSegment = WorkoutTemplateSegment(
        templateId = 0, sortOrder = 0, kind = SegmentKind.WORK, title = "Tempo",
        goalType = SegmentGoalType.DURATION, durationMs = 600_000L, targetHrZone = 4
    )
    private val paceSegment = WorkoutTemplateSegment(
        templateId = 0, sortOrder = 1, kind = SegmentKind.WORK, title = "Fast",
        goalType = SegmentGoalType.DISTANCE, distanceMeters = 400f, targetPaceMinPerKm = 4.5f
    )

    @Test
    fun `a zone target goes there and back`() {
        val parsed = IntervalSegmentsJson.parse(IntervalSegmentsJson.toJson(listOf(zoneSegment, paceSegment)))

        assertEquals(listOf(zoneSegment, paceSegment), parsed)
        assertEquals(SegmentTarget.HrZone(4), parsed[0].target)
        assertEquals(SegmentTarget.Pace(4.5f), parsed[1].target)
    }

    @Test
    fun `no zone key is written for a segment without a zone`() {
        val json = requireNotNull(IntervalSegmentsJson.toJson(listOf(paceSegment)))
        assertFalse(json.contains("targetHrZone"))
    }

    @Test
    fun `an old snapshot without the key reads as before`() {
        val old = """[{"sortOrder":0,"kind":"WORK","title":"Fast","goalType":"DISTANCE","distanceMeters":400.0,"targetPaceMinPerKm":4.5},""" +
            """{"sortOrder":1,"kind":"RECOVERY","title":"Jog","goalType":"DURATION","durationMs":90000}]"""

        val parsed = IntervalSegmentsJson.parse(old)

        assertEquals(SegmentTarget.Pace(4.5f), parsed[0].target)
        assertEquals(null, parsed[0].targetHrZone)
        assertEquals(SegmentTarget.None, parsed[1].target)
    }

    @Test
    fun `both fields - the zone wins and the pace is dropped`() {
        val both = """[{"sortOrder":0,"kind":"WORK","title":"W","goalType":"DURATION","durationMs":60000,"targetPaceMinPerKm":4.5,"targetHrZone":3}]"""

        val segment = IntervalSegmentsJson.parse(both).single()

        assertEquals(SegmentTarget.HrZone(3), segment.target)
        assertEquals(null, segment.targetPaceMinPerKm)
    }

    @Test
    fun `a zone out of 1 to 5 is dropped and the pace kept`() {
        val bad = """[{"sortOrder":0,"kind":"WORK","title":"W","goalType":"DURATION","durationMs":60000,"targetPaceMinPerKm":4.5,"targetHrZone":7}]"""

        val segment = IntervalSegmentsJson.parse(bad).single()

        assertEquals(null, segment.targetHrZone)
        assertEquals(SegmentTarget.Pace(4.5f), segment.target)
    }
}
