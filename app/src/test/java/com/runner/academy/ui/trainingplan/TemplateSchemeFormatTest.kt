package com.runner.academy.ui.trainingplan

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.runner.academy.data.SegmentGoalType
import com.runner.academy.data.SegmentKind
import com.runner.academy.data.WorkoutTemplateSegment
import com.runner.academy.util.HrZones
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Segment params: «Work 03:00 @ 4:30» for a pace, «Work 03:00 · Z4» for a zone (design §2). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TemplateSchemeFormatTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val work = WorkoutTemplateSegment(
        templateId = 1, sortOrder = 0, kind = SegmentKind.WORK, title = "Fast",
        goalType = SegmentGoalType.DURATION, durationMs = 180_000L
    )

    @Test
    fun `a pace target - as before`() {
        assertEquals("Fast 03:00 @ 4:30", formatSegmentParams(context, work.copy(targetPaceMinPerKm = 4.5f)))
    }

    @Test
    fun `a zone target - short zone badge`() {
        assertEquals("Fast 03:00 · Z4", formatSegmentParams(context, work.copy(targetHrZone = 4)))
    }

    @Test
    fun `no target - only the goal`() {
        assertEquals("Fast 03:00", formatSegmentParams(context, work))
        assertEquals("Fast 03:00", formatSegmentParams(context, work, forA11y = true))
    }

    @Test
    fun `for TalkBack the zone is spelled out`() {
        assertEquals("Fast 03:00 · zone 4", formatSegmentParams(context, work.copy(targetHrZone = 4), forA11y = true))
        assertEquals("Fast 03:00 @ 4:30", formatSegmentParams(context, work.copy(targetPaceMinPerKm = 4.5f), forA11y = true))
    }

    @Test
    fun `both fields - the zone wins`() {
        assertEquals("Fast 03:00 · Z2", formatSegmentParams(context, work.copy(targetHrZone = 2, targetPaceMinPerKm = 4.5f)))
    }

    @Test
    fun `the zone field shows the range by the current maximum, or the zone alone`() {
        assertEquals("Zone 4 · 152–170 bpm", formatSegmentZone(context, 4, HrZones(190)))
        assertEquals("Zone 4", formatSegmentZone(context, 4, null))
    }

    @Test
    @Config(qualifiers = "ru")
    fun `the zone picker items - range and name`() {
        assertEquals(
            listOf(
                "Зона 1 · 95–113 уд/мин · восстановление",
                "Зона 2 · 114–132 уд/мин · аэробная база",
                "Зона 3 · 133–151 уд/мин · темповая",
                "Зона 4 · 152–170 уд/мин · пороговая",
                "Зона 5 · 171–190 уд/мин · максимум"
            ),
            zonePickerItems(context, HrZones(190))
        )
    }

    @Test
    @Config(qualifiers = "ru")
    fun `russian scheme for TalkBack`() {
        val segments = listOf(work.copy(targetHrZone = 4), work.copy(sortOrder = 1, targetPaceMinPerKm = 4.5f))

        assertEquals("Fast 03:00 · Z4 → Fast 03:00 @ 4:30", formatTemplateScheme(context, segments))
        assertEquals("Fast 03:00 · зона 4 → Fast 03:00 @ 4:30", formatTemplateScheme(context, segments, forA11y = true))
    }
}
