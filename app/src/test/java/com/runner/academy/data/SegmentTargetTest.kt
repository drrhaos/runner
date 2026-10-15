package com.runner.academy.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** One target per segment, computed from the two nullable columns (architecture §06). */
class SegmentTargetTest {

    private val base = WorkoutTemplateSegment(templateId = 1, sortOrder = 0, title = "Work")

    @Test
    fun `no pace and no zone is no target`() {
        assertEquals(SegmentTarget.None, base.target)
    }

    @Test
    fun `a positive pace is a pace target`() {
        assertEquals(SegmentTarget.Pace(4.5f), base.copy(targetPaceMinPerKm = 4.5f).target)
    }

    @Test
    fun `a zero pace is no target`() {
        assertEquals(SegmentTarget.None, base.copy(targetPaceMinPerKm = 0f).target)
    }

    @Test
    fun `a zone 1 to 5 is a zone target`() {
        (1..5).forEach { zone ->
            assertEquals(SegmentTarget.HrZone(zone), base.copy(targetHrZone = zone).target)
        }
    }

    @Test
    fun `a zone out of 1 to 5 is ignored`() {
        assertEquals(SegmentTarget.None, base.copy(targetHrZone = 0).target)
        assertEquals(SegmentTarget.None, base.copy(targetHrZone = 6).target)
        assertEquals(SegmentTarget.Pace(5f), base.copy(targetHrZone = 9, targetPaceMinPerKm = 5f).target)
    }

    @Test
    fun `the zone wins when both are set`() {
        assertEquals(SegmentTarget.HrZone(4), base.copy(targetHrZone = 4, targetPaceMinPerKm = 4.5f).target)
    }

    @Test
    fun `validZoneOrNull keeps only 1 to 5`() {
        assertEquals(null, SegmentTarget.validZoneOrNull(null))
        assertEquals(null, SegmentTarget.validZoneOrNull(0))
        assertEquals(3, SegmentTarget.validZoneOrNull(3))
        assertEquals(null, SegmentTarget.validZoneOrNull(6))
    }
}
