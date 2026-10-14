package com.runner.academy.voice

import com.runner.academy.data.SegmentGoalType
import com.runner.academy.data.SegmentKind
import com.runner.academy.data.WorkoutTemplateSegment
import com.runner.academy.util.IntervalEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCoachTest {

    private val coach = VoiceCoach()
    private val on = VoiceSettings.DEFAULT.copy(enabled = true)

    private fun timed(sortOrder: Int, sec: Int, kind: SegmentKind = SegmentKind.WORK) = WorkoutTemplateSegment(
        templateId = 1L, sortOrder = sortOrder, kind = kind, title = "S$sortOrder",
        goalType = SegmentGoalType.DURATION, durationMs = sec * 1_000L
    )

    private fun input(
        sec: Int,
        distanceM: Float = sec * 10f / 3f,
        settings: VoiceSettings = on,
        engine: IntervalEngine? = null
    ) = CoachInput(
        elapsedMs = sec * 1_000L,
        movingMs = sec * 1_000L,
        distanceM = distanceM,
        autoPaused = false,
        avgPace = if (distanceM > 0f) (sec / 60f) / (distanceM / 1_000f) else 0f,
        settings = settings,
        heartRate = null,
        engine = engine
    )

    /** Ticks seconds [from]..[to]; (second, lines) of the ticks that said something. */
    private fun run(
        to: Int,
        from: Int = 1,
        settings: VoiceSettings = on,
        engine: IntervalEngine? = null,
        distanceAt: (Int) -> Float = { it * 10f / 3f }
    ): List<Pair<Int, List<VoiceLine>>> = (from..to).mapNotNull { sec ->
        coach.onTick(input(sec, distanceAt(sec), settings, engine)).lines.takeIf { it.isNotEmpty() }?.let { sec to it }
    }

    @Test
    fun `a free run speaks the kilometre summary`() {
        val said = run(to = 650)

        assertEquals(listOf(300, 600), said.map { it.first })
        assertTrue(said.all { (_, lines) -> lines.single() is VoiceLine.Summary })
    }

    @Test
    fun `a 2 min segment by time with no distance at all - warning at 90 s, beep at 120 s`() {
        val engine = IntervalEngine(listOf(timed(0, 120), timed(1, 60, SegmentKind.RECOVERY)))
        // GPS silent and no steps: the distance never moves
        val said = run(to = 200, engine = engine) { 0f }

        assertEquals(
            listOf(
                1 to listOf(VoiceLine.SegmentStart),
                90 to listOf(VoiceLine.SegmentUpcoming(engine.allSegments()[1])),
                120 to listOf(VoiceLine.SegmentStart)
            ),
            said
        )
    }

    @Test
    fun `the warning comes before the summary of the same tick`() {
        val settings = on.copy(intervals = on.intervals.copy(summary = SummaryEvery.MIN_1))
        val engine = IntervalEngine(listOf(timed(0, 90), timed(1, 60)))

        val tick60 = run(from = 1, to = 60, settings = settings, engine = engine).last()

        assertEquals(60, tick60.first)
        assertEquals(VoiceLine.SegmentUpcoming(engine.allSegments()[1]), tick60.second[0])
        assertTrue(tick60.second[1] is VoiceLine.Summary)
        assertEquals(2, tick60.second.size)
    }

    @Test
    fun `the segment beep comes before the summary of the same tick`() {
        val settings = on.copy(intervals = on.intervals.copy(summary = SummaryEvery.MIN_1))
        val engine = IntervalEngine(listOf(timed(0, 60), timed(1, 60)))

        val tick60 = run(to = 60, settings = settings, engine = engine).last()

        assertEquals(VoiceLine.SegmentStart, tick60.second[0])
        assertTrue(tick60.second[1] is VoiceLine.Summary)
    }

    @Test
    fun `with segments the interval profile applies - no kilometre summary by default`() {
        val engine = IntervalEngine(listOf(timed(0, 3_600)))
        val said = run(to = 700, engine = engine)

        assertEquals(listOf(1 to listOf<VoiceLine>(VoiceLine.SegmentStart)), said)
    }

    @Test
    fun `segment change off - silent, the segments still advance`() {
        val settings = on.copy(intervals = on.intervals.copy(segmentChange = false))
        val engine = IntervalEngine(listOf(timed(0, 60), timed(1, 60)))

        assertEquals(emptyList<Pair<Int, List<VoiceLine>>>(), run(to = 70, settings = settings, engine = engine))
        assertEquals(1, engine.currentIndex())
    }

    @Test
    fun `voice off - silent, and switching it on later says nothing for the past`() {
        val engine = IntervalEngine(listOf(timed(0, 60), timed(1, 600)))
        val off = on.copy(enabled = false, intervals = on.intervals.copy(summary = SummaryEvery.KM_1))
        assertEquals(emptyList<Pair<Int, List<VoiceLine>>>(), run(to = 400, settings = off, engine = engine))
        assertEquals(1, engine.currentIndex())

        // On at 1 333 m: the next summary at 2 km, no beep for the segment already started
        val switchedOn = off.copy(enabled = true)
        assertEquals(listOf(600), run(from = 401, to = 610, settings = switchedOn, engine = engine).map { it.first })
    }

    @Test
    fun `a resume after a restore speaks no summary for the past`() {
        coach.resume(movingMs = 1_020_000L, distanceM = 3_400f)

        val said = run(from = 1_021, to = 1_200) { 3_400f + (it - 1_020) * 10f / 3f }

        assertEquals(listOf(1_200), said.map { it.first })
    }

    @Test
    fun `a restored cursor neither beeps nor warns again`() {
        val first = IntervalEngine(listOf(timed(0, 120), timed(1, 60)))
        run(to = 100, engine = first)
        val restored = IntervalEngine(first.allSegments()).apply { restore(first.snapshot()) }

        val again = VoiceCoach().apply { resume(100_000L, 333f) }
        val said = (101..130).mapNotNull { sec ->
            again.onTick(input(sec, engine = restored)).lines.takeIf { it.isNotEmpty() }?.let { sec to it }
        }

        assertEquals(listOf(120 to listOf<VoiceLine>(VoiceLine.SegmentStart)), said)
    }
}
