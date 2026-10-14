package com.runner.academy.voice

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.runner.academy.data.SegmentGoalType
import com.runner.academy.data.SegmentKind
import com.runner.academy.data.WorkoutTemplateSegment
import com.runner.academy.util.FormatUtils
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The words of each [VoiceLine], English and Russian (design §6). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class VoiceLineTextTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val kmSummary = VoiceLine.Summary(
        listOf(SummaryPart.Distance(3_503f), SummaryPart.Time(1_052_000L), SummaryPart.AvgPace(5f + 1f / 60f))
    )
    private val allSplits = VoiceLine.Summary(
        listOf(
            SummaryPart.SplitPace(4f + 58f / 60f, SplitSpan.Every(SummaryEvery.KM_1)),
            SummaryPart.SplitHr(152, SplitSpan.Every(SummaryEvery.MIN_5)),
            SummaryPart.CurrentHr(158)
        )
    )
    private val partialSplits = VoiceLine.Summary(
        listOf(SummaryPart.SplitPace(5f, SplitSpan.SinceLastSummary), SummaryPart.SplitHr(150, SplitSpan.SinceLastSummary))
    )
    private val fromStart = VoiceLine.Summary(
        listOf(SummaryPart.SplitPace(5f, SplitSpan.SinceStart), SummaryPart.SplitHr(150, SplitSpan.SinceStart))
    )
    private val upcoming = VoiceLine.SegmentUpcoming(
        WorkoutTemplateSegment(
            templateId = 1L, sortOrder = 1, kind = SegmentKind.WORK, title = "Fast 400",
            goalType = SegmentGoalType.DISTANCE, distanceMeters = 400f, targetPaceMinPerKm = 4.5f
        )
    )

    private fun render(line: VoiceLine) = VoiceLineText.render(line, context)

    @Test
    fun `the summary in english, one part per metric`() {
        assertEquals(
            listOf("Distance 3 kilometers 500 meters", "Time 17 minutes 32 seconds", "Average pace 5 minutes 1 second per kilometer"),
            render(kmSummary)
        )
        assertEquals(
            listOf(
                "Pace for the last kilometer: 4 minutes 58 seconds per kilometer",
                "Average heart rate for the last 5 minutes: 152",
                "Heart rate 158"
            ),
            render(allSplits)
        )
        assertEquals(
            listOf("Pace since the last summary: 5 minutes per kilometer", "Average heart rate since the last summary: 150"),
            render(partialSplits)
        )
        assertEquals(
            listOf("Pace since the start: 5 minutes per kilometer", "Average heart rate since the start: 150"),
            render(fromStart)
        )
    }

    @Test
    @Config(qualifiers = "ru")
    fun `the summary in russian`() {
        assertEquals(
            listOf("Дистанция 3 километра 500 метров", "Время 17 минут 32 секунды", "Средний темп 5 минут 1 секунда на километр"),
            render(kmSummary)
        )
        assertEquals(
            listOf("Темп за последний километр: 4 минуты 58 секунд на километр", "Средний пульс за последние 5 минут: 152", "Пульс 158"),
            render(allSplits)
        )
        assertEquals(
            listOf("Темп с прошлой сводки: 5 минут на километр", "Средний пульс с прошлой сводки: 150"),
            render(partialSplits)
        )
        assertEquals(
            listOf("Темп с начала пробежки: 5 минут на километр", "Средний пульс с начала пробежки: 150"),
            render(fromStart)
        )
    }

    @Test
    fun `whole kilometres and short distances drop the zero part`() {
        assertEquals(listOf("Distance 2 kilometers"), render(VoiceLine.Summary(listOf(SummaryPart.Distance(2_003f)))))
        assertEquals(listOf("Distance 500 meters"), render(VoiceLine.Summary(listOf(SummaryPart.Distance(498f)))))
    }

    @Test
    fun `every summary interval has its span words`() {
        SummaryEvery.entries.filter { it != SummaryEvery.OFF }.forEach { every ->
            val text = render(VoiceLine.Summary(listOf(SummaryPart.SplitHr(140, SplitSpan.Every(every))))).single()
            assertEquals(every.name, true, text.startsWith("Average heart rate for the last"))
        }
    }

    @Test
    fun `the 30 s warning - title, goal and pace as three parts`() {
        assertEquals(listOf("In 30 seconds: Fast 400", "Distance 400 meters", "Pace 4 minutes 30 seconds per kilometer"), render(upcoming))
    }

    @Test
    @Config(qualifiers = "ru")
    fun `the 30 s warning in russian, without a pace when the segment has none`() {
        val line = VoiceLine.SegmentUpcoming(upcoming.segment.copy(targetPaceMinPerKm = null, goalType = SegmentGoalType.DURATION, durationMs = 90_000L))
        assertEquals(listOf("Через 30 секунд начнётся интервал: Fast 400", "Время 1 минута 30 секунд"), render(line))
    }

    @Test
    fun `the 30 s warning with a zone target says the zone`() {
        val line = VoiceLine.SegmentUpcoming(upcoming.segment.copy(targetPaceMinPerKm = null, targetHrZone = 4))
        assertEquals(listOf("In 30 seconds: Fast 400", "Distance 400 meters", "Zone 4"), render(line))
    }

    @Test
    @Config(qualifiers = "ru")
    fun `the 30 s warning in russian with a zone target`() {
        val line = VoiceLine.SegmentUpcoming(upcoming.segment.copy(targetPaceMinPerKm = 4.5f, targetHrZone = 2))
        assertEquals(listOf("Через 30 секунд начнётся интервал: Fast 400", "Дистанция 400 метров", "Зона 2"), render(line))
    }

    @Test
    fun `status lines and the beep`() {
        assertEquals(listOf("GPS signal lost"), render(VoiceLine.Status(StatusKind.GPS_LOST)))
        assertEquals(listOf("Heart rate sensor connected"), render(VoiceLine.Status(StatusKind.HR_CONNECTED)))
        assertEquals(listOf("Auto-pause"), render(VoiceLine.Status(StatusKind.AUTO_PAUSE)))
        StatusKind.entries.forEach { assertEquals(1, render(VoiceLine.Status(it)).size) }
        assertEquals(emptyList<String>(), render(VoiceLine.SegmentStart))
    }

    @Test
    @Config(qualifiers = "ru")
    fun `the pace without the unit for the target cues`() {
        assertEquals("4 минуты 52 секунды", FormatUtils.formatPaceForTTS(4f + 52f / 60f, context, perUnit = false))
        assertEquals("4 минуты 52 секунды на километр", FormatUtils.formatPaceForTTS(4f + 52f / 60f, context))
    }
}
