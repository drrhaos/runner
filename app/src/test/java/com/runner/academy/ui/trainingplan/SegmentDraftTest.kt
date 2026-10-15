package com.runner.academy.ui.trainingplan

import com.runner.academy.data.SegmentGoalType
import com.runner.academy.data.SegmentKind
import com.runner.academy.data.SegmentTarget
import com.runner.academy.data.WorkoutTemplateSegment
import com.runner.academy.data.target
import com.runner.academy.util.HrZones
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The editor draft remembers both values; [SegmentDraft.toEntity] keeps one target (architecture §06). */
class SegmentDraftTest {

    @Test
    fun `a zone draft drops the remembered pace`() {
        val entity = SegmentDraft(targetKind = TargetKind.ZONE, hrZone = 4, paceTotalSeconds = 270).toEntity(1L, 0)

        assertEquals(null, entity.targetPaceMinPerKm)
        assertEquals(4, entity.targetHrZone)
        assertEquals(SegmentTarget.HrZone(4), entity.target)
    }

    @Test
    fun `a pace draft drops the remembered zone`() {
        val entity = SegmentDraft(targetKind = TargetKind.PACE, hrZone = 2, paceTotalSeconds = 270).toEntity(1L, 0)

        assertEquals(4.5f, entity.targetPaceMinPerKm!!, 0.0001f)
        assertEquals(null, entity.targetHrZone)
    }

    @Test
    fun `none drops both, and a pace kind without a pace is none`() {
        val none = SegmentDraft(targetKind = TargetKind.NONE, hrZone = 2, paceTotalSeconds = 270).toEntity(1L, 0)
        val emptyPace = SegmentDraft(targetKind = TargetKind.PACE, paceTotalSeconds = 0).toEntity(1L, 0)

        assertEquals(SegmentTarget.None, none.target)
        assertEquals(null, none.targetPaceMinPerKm)
        assertEquals(null, none.targetHrZone)
        assertEquals(SegmentTarget.None, emptyPace.target)
    }

    @Test
    fun `the kind follows the pace when not given - the wizard keeps its paces`() {
        assertEquals(TargetKind.PACE, SegmentDraft(paceTotalSeconds = 300).targetKind)
        assertEquals(TargetKind.NONE, SegmentDraft().targetKind)
        assertEquals(3, SegmentDraft().hrZone)
    }

    @Test
    fun `fromEntity restores the target kind`() {
        val base = WorkoutTemplateSegment(
            templateId = 1, sortOrder = 0, kind = SegmentKind.WORK, title = "W",
            goalType = SegmentGoalType.DURATION, durationMs = 60_000L
        )

        val zone = SegmentDraft.fromEntity(base.copy(targetHrZone = 4))
        val pace = SegmentDraft.fromEntity(base.copy(targetPaceMinPerKm = 4.5f))
        val none = SegmentDraft.fromEntity(base)

        assertEquals(TargetKind.ZONE, zone.targetKind)
        assertEquals(4, zone.hrZone)
        assertEquals(TargetKind.PACE, pace.targetKind)
        assertEquals(270, pace.paceTotalSeconds)
        assertEquals(TargetKind.NONE, none.targetKind)
        assertEquals(3, none.hrZone)
    }

    @Test
    fun `entity - draft - entity keeps a zone target`() {
        val segment = WorkoutTemplateSegment(
            templateId = 0, sortOrder = 2, kind = SegmentKind.WORK, title = "Tempo",
            goalType = SegmentGoalType.DISTANCE, distanceMeters = 2000f, targetHrZone = 5
        )

        assertEquals(segment, SegmentDraft.fromEntity(segment).toEntity(0, 2))
    }

    @Test
    fun `zones on - zone button enabled, the picker shows these zones`() {
        val ui = ZoneTargetUi.of(TargetKind.NONE, HrZones(190))

        assertTrue(ui.zoneButtonEnabled)
        assertTrue(ui.pickerAllowed)
        assertEquals(HrZones(190), ui.pickerZones)
    }

    @Test
    fun `zones off - zone button disabled, no picker`() {
        val ui = ZoneTargetUi.of(TargetKind.PACE, null)

        assertFalse(ui.zoneButtonEnabled)
        assertFalse(ui.pickerAllowed)
    }

    @Test
    fun `zones off with a saved zone - the zone stays selectable, no picker`() {
        val ui = ZoneTargetUi.of(TargetKind.ZONE, null)

        assertTrue(ui.zoneButtonEnabled)
        assertFalse(ui.pickerAllowed)
    }
}
