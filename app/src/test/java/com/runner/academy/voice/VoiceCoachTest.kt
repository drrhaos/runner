package com.runner.academy.voice

import com.runner.academy.data.SegmentGoalType
import com.runner.academy.data.SegmentKind
import com.runner.academy.data.WorkoutTemplateSegment
import com.runner.academy.service.LivePaceTracker
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

    // -- The target check (architecture §04) --

    private fun paced(sortOrder: Int, sec: Int, paceMinPerKm: Float?, kind: SegmentKind = SegmentKind.WORK) =
        timed(sortOrder, sec, kind).copy(targetPaceMinPerKm = paceMinPerKm)

    /** 4:48 per km all along (18 s off a 4:30 target): 1 000 / 288 m a second. */
    private val slow = 1_000f / 288f

    /**
     * Ticks [from]..[to] at [mps] with a live-pace tracker fed like the service's; the results,
     * by second. [busyAt]: the speech engine is talking on that second.
     */
    private fun runTarget(
        engine: IntervalEngine,
        to: Int,
        from: Int = 1,
        settings: VoiceSettings = on,
        mps: Float = slow,
        tracker: LivePaceTracker = LivePaceTracker(stride = null),
        busyAt: (Int) -> Boolean = { false }
    ): List<Pair<Int, CoachResult>> = (from..to).map { sec ->
        val distanceM = sec * mps
        tracker.onTick(sec * 1_000L, distanceM, steps = null, gpsFresh = true)
        sec to coach.onTick(
            input(sec, distanceM, settings, engine).copy(livePace = tracker, speechBusy = busyAt(sec))
        )
    }

    private fun List<Pair<Int, CoachResult>>.hints() =
        filter { (_, r) -> r.lines.any { it is VoiceLine.TargetHint } }.map { it.first }

    @Test
    fun `a 4 30 target run at 4 48 - too slow once the window is full, the hint after the hold and every 30 s`() {
        val engine = IntervalEngine(listOf(paced(0, 600, 4.5f)))

        val ticks = runTarget(engine, to = 95)

        // The first tick is at 1 s, so the 15 s window is full at 16 s
        assertEquals(listOf(31, 61, 91), ticks.hints())
        val at31 = ticks.single { it.first == 31 }.second
        val hint = at31.lines.single() as VoiceLine.TargetHint
        assertEquals(TargetVerdict.TOO_SLOW, hint.verdict)
        assertEquals(4.8f, hint.pace!!, 0.01f)
        assertEquals(TargetVerdict.PENDING, ticks.single { it.first == 15 }.second.interval!!.target.verdict)
        assertEquals(TargetVerdict.TOO_SLOW, ticks.single { it.first == 16 }.second.interval!!.target.verdict)
        val live = at31.interval!!
        assertEquals(0, live.segmentIndex)
        assertEquals(31_000L, live.segmentElapsedMs)
        assertTrue(live.hintDue && live.hintVoiced)
        assertTrue(ticks.filter { it.first !in listOf(31, 61, 91) }.none { it.second.interval!!.hintDue })
    }

    @Test
    fun `the warning and the summary in one tick - the hint drops, and plays on the first quiet tick`() {
        // The warning and a minute summary at 60 s (a 90 s segment); the hint is due from 31 s on
        // but the speech is busy until 60 s and while those two are said (61–63)
        val settings = on.copy(intervals = on.intervals.copy(summary = SummaryEvery.MIN_1))
        val engine = IntervalEngine(listOf(paced(0, 90, 4.5f), paced(1, 600, 4.5f)))

        val ticks = runTarget(engine, to = 70, settings = settings, busyAt = { it < 60 || it in 61..63 })

        val at60 = ticks.single { it.first == 60 }.second.lines
        assertEquals(VoiceLine.SegmentUpcoming(engine.allSegments()[1]), at60[0])
        assertTrue(at60[1] is VoiceLine.Summary)
        assertEquals(2, at60.size)
        assertEquals(listOf(64), ticks.hints())
        assertTrue(ticks.single { it.first == 64 }.second.interval!!.hintVoiced)
        assertTrue(ticks.filter { it.first < 64 }.none { it.second.interval!!.hintDue })
    }

    @Test
    fun `the last 10 s of a segment - no hint`() {
        // A 41 s segment: the hold ends at 31 (10 s left: none), the next segment has no target
        val engine = IntervalEngine(listOf(paced(0, 41, 4.5f), paced(1, 600, null, SegmentKind.RECOVERY)))

        val ticks = runTarget(engine, to = 120)

        assertEquals(emptyList<Int>(), ticks.hints())
        assertEquals(TargetVerdict.NO_TARGET, ticks.last().second.interval!!.target.verdict)
    }

    @Test
    fun `voice off - the verdict and the due hint for the screen, nothing said`() {
        val engine = IntervalEngine(listOf(paced(0, 600, 4.5f)))

        val ticks = runTarget(engine, to = 65, settings = on.copy(enabled = false))

        assertTrue(ticks.all { it.second.lines.isEmpty() })
        assertEquals(listOf(31, 61), ticks.filter { it.second.interval!!.hintDue }.map { it.first })
        assertTrue(ticks.none { it.second.interval!!.hintVoiced })
    }

    @Test
    fun `target cues off - silent, the due hint left to the screen`() {
        val settings = on.copy(intervals = on.intervals.copy(targetCheck = false))
        val engine = IntervalEngine(listOf(paced(0, 600, 4.5f)))

        val ticks = runTarget(engine, to = 65, settings = settings)

        assertEquals(listOf(1), ticks.filter { it.second.lines.isNotEmpty() }.map { it.first })
        assertEquals(listOf(31, 61), ticks.filter { it.second.interval!!.hintDue }.map { it.first })
    }

    @Test
    fun `the window is cut at the segment start - the warm-up pace does not count`() {
        // 60 s of warm-up at 6:00 without a target, then 4:30 for a 4:30 target
        val engine = IntervalEngine(listOf(paced(0, 60, null, SegmentKind.WARMUP), paced(1, 600, 4.5f)))
        val tracker = LivePaceTracker(stride = null)
        var distance = 0f
        val verdicts = (1..100).map { sec ->
            distance += if (sec <= 60) 1_000f / 360f else 1_000f / 270f
            tracker.onTick(sec * 1_000L, distance, steps = null, gpsFresh = true)
            sec to coach.onTick(input(sec, distance, on, engine).copy(livePace = tracker)).interval!!.target.verdict
        }.toMap()

        assertEquals(TargetVerdict.NO_TARGET, verdicts[59])
        assertEquals(TargetVerdict.PENDING, verdicts[70])
        assertTrue((75..100).all { verdicts[it] == TargetVerdict.IN })
    }

    @Test
    fun `a manual resume mid-segment - pending while the window refills, then the verdict`() {
        val engine = IntervalEngine(listOf(paced(0, 600, 4.5f)))
        val tracker = LivePaceTracker(stride = null)
        runTarget(engine, to = 100, tracker = tracker)
        // Resume: the service starts the window over where the moving clock stood
        tracker.restart(atMovingMs = 100_000L)

        val ticks = runTarget(engine, from = 101, to = 120, tracker = tracker).toMap()

        assertTrue((101..115).all { ticks.getValue(it).interval!!.target.verdict == TargetVerdict.PENDING })
        assertEquals(TargetVerdict.TOO_SLOW, ticks.getValue(116).interval!!.target.verdict)
    }

    @Test
    fun `voice on but the speech engine dead - nothing said, the due hint left to the screen`() {
        val engine = IntervalEngine(listOf(paced(0, 600, 4.5f)))
        val tracker = LivePaceTracker(stride = null)

        val ticks = (1..65).map { sec ->
            val distanceM = sec * slow
            tracker.onTick(sec * 1_000L, distanceM, steps = null, gpsFresh = true)
            sec to coach.onTick(input(sec, distanceM, on, engine).copy(livePace = tracker, canSpeak = false))
        }

        assertEquals(emptyList<Int>(), ticks.hints())
        assertEquals(listOf(31, 61), ticks.filter { it.second.interval!!.hintDue }.map { it.first })
        assertTrue(ticks.none { it.second.interval!!.hintVoiced })
    }

    @Test
    fun `a free run has no interval state`() {
        assertEquals(null, coach.onTick(input(10)).interval)
    }
}
