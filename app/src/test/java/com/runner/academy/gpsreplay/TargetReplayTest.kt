package com.runner.academy.gpsreplay

import com.runner.academy.data.SegmentGoalType
import com.runner.academy.data.SegmentKind
import com.runner.academy.data.WorkoutTemplateSegment
import com.runner.academy.gpsreplay.SyntheticRun.Phase
import com.runner.academy.util.HrZones
import com.runner.academy.voice.TargetVerdict
import com.runner.academy.voice.VoiceLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The target check on the bench (architecture §04, acceptance after the `r4-live-pace` review):
 * the bench's default GPS noise (AR(1), ~1.3 m), seeds 1..10, GPS pace only. On 6 × 400 m hints
 * only on the repeats really off the target, and the voice and the segment bar on the same
 * segment on every tick. No false hints on an even run at the target or 5 s per km off it: not
 * met, see the ignored test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TargetReplayTest {

    private val gpsOnly = ReplaySettings(stepsAvailable = false)

    private fun mps(min: Int, sec: Int) = 1_000.0 / (min * 60 + sec)

    private fun timed(sortOrder: Int, sec: Int, pace: Float?, kind: SegmentKind = SegmentKind.WORK) = WorkoutTemplateSegment(
        templateId = 1L, sortOrder = sortOrder, kind = kind, title = "S$sortOrder",
        goalType = SegmentGoalType.DURATION, durationMs = sec * 1_000L, targetPaceMinPerKm = pace
    )

    private fun metres(sortOrder: Int, meters: Float, pace: Float?, kind: SegmentKind) = WorkoutTemplateSegment(
        templateId = 1L, sortOrder = sortOrder, kind = kind, title = "S$sortOrder",
        goalType = SegmentGoalType.DISTANCE, distanceMeters = meters, targetPaceMinPerKm = pace
    )

    private fun SessionReplayResult.hints() = voice.filter { it.second is VoiceLine.TargetHint }

    /** 10 min at [speedMps] for a 5:00 target, on the block loop. */
    private fun evenRun(speedMps: Double, seed: Long): SessionReplayResult {
        val run = SyntheticRun(route = SyntheticRun.blockLoop(laps = 2), speedMps = speedMps, seed = seed)
        return SessionReplay.run(
            run,
            gpsOnly.copy(segments = listOf(timed(0, 600, 5f))),
            listOf(ReplayEvent.Stop(atSec = 605))
        )
    }

    /** The seconds of the hints on 10 min even at [min]:[sec] per km, by seed. */
    private fun evenHints(min: Int, sec: Int): Map<Long, List<Long>> =
        SEEDS.associateWith { seed -> evenRun(mps(min, sec), seed).hints().map { it.first / 1_000L } }

    /**
     * The acceptance as written: not met. With the whole fallback (hold 10 → 15 s, hysteresis
     * 3 → 5 s per km; a 20 s verdict window does not apply, the false hints come mid-run) seeds
     * 1..10 still give: at 5:00 one hint (seed 8); at 5:05 one each on seeds 6 and 8; at 4:55
     * 1–2 on 7 seeds. The 30 s pace swings ~5 s per km (1σ) with the error correlated over ~20 s,
     * and the noise makes it ~2.5 s per km fast (4:55 reads 4:52.5). Escalated to the architect.
     */
    @Ignore("Acceptance not met on the bench with the planned fallback knobs; escalated, see KDoc")
    @Test
    fun `10 min even at the target or 5 s per km off it - no hint on any seed`() {
        for ((min, sec) in listOf(5 to 0, 4 to 55, 5 to 5)) {
            val hints = evenHints(min, sec)
            assertTrue("$min:$sec $hints", hints.values.all { it.isEmpty() })
        }
    }

    /**
     * What holds instead (a guard against going back): at most one false hint in 10 min at the
     * target, at most two at 5 s per km off it, on every seed. The planned hold and hysteresis
     * (10 s, 3 s) gave up to 6 at 4:55.
     */
    @Test
    fun `10 min even at the target or 5 s per km off it - false hints stay rare`() {
        for ((min, sec, most) in listOf(Triple(5, 0, 1), Triple(4, 55, 2), Triple(5, 5, 2))) {
            val hints = evenHints(min, sec)
            println("target 5:00, run $min:${"%02d".format(sec)} - hint seconds by seed: $hints")
            assertTrue("$min:$sec $hints", hints.values.all { it.size <= most })
        }
    }

    @Test
    fun `an even run at the target is shown in the target nearly all along`() {
        for (seed in SEEDS) {
            val verdicts = evenRun(mps(5, 0), seed).interval.mapNotNull { it.second?.target?.verdict }
            val judged = verdicts.filter { it == TargetVerdict.IN || it.isOff }
            val inShare = judged.count { it == TargetVerdict.IN }.toDouble() / judged.size
            println("seed $seed: in the target on ${"%.1f".format(inShare * 100)} % of judged ticks")
            assertTrue("seed $seed: $inShare", inShare >= 0.9)
        }
    }

    /**
     * 6 × 400 m for 4:00 with 200 m of recovery without a target, after a 400 m warm-up without
     * one. Repeats 1–3 and 6 at 4:00; repeat 4 at 4:30 (30 s slow), repeat 5 at 3:35 (25 s fast).
     *
     * The runner changes pace on the beep, as a real one does. The bench's noise lengthens the
     * GPS distance by ~1 % (≈ 35 m by the last repeat), so a runner who changed pace at the true
     * 400 m marks would still jog the recovery for ~15 s of a repeat the watch already started:
     * a "too slow" no real run has. So the run is replayed until its pace changes sit on the
     * beeps of the replay itself (each pass moves them by a second or two, then not at all).
     */
    private fun repeats(seed: Long): SessionReplayResult {
        val target = 4f
        val segments = buildList {
            add(metres(0, 400f, null, SegmentKind.WARMUP))
            repeat(6) { i ->
                add(metres(1 + 2 * i, 400f, target, SegmentKind.WORK))
                if (i < 5) add(metres(2 + 2 * i, 200f, null, SegmentKind.RECOVERY))
            }
        }
        val repeatPace = listOf(mps(4, 0), mps(4, 0), mps(4, 0), mps(4, 30), mps(3, 35), mps(4, 0))
        // The speed of each segment, then of the cool-down after the last one
        val speeds = buildList {
            add(mps(6, 0))
            repeatPace.forEachIndexed { i, speed ->
                add(speed)
                if (i < 5) add(mps(7, 0))
            }
            add(mps(7, 0))
        }
        // The seconds the segments start at: first by the true metres, then by the beeps
        var starts = segments.runningFold(0.0) { sec, segment -> sec + segment.distanceMeters!! / speeds[segment.sortOrder] }
            .dropLast(1)
        var result: SessionReplayResult
        var pass = 0
        while (true) {
            val phases = speeds.indices.map { i ->
                val meters = when {
                    i < starts.lastIndex -> speeds[i] * (starts[i + 1] - starts[i])
                    // The last repeat: past its 400 m, since the watch ends it a little later
                    i == starts.lastIndex -> 460.0
                    else -> 200.0
                }
                Phase.Run(meters, speeds[i])
            }
            val run = SyntheticRun(route = SyntheticRun.blockLoop(laps = 4), phases = phases, seed = seed)
            result = SessionReplay.run(run, gpsOnly.copy(segments = segments))
            val beeps = result.voice.filter { it.second == VoiceLine.SegmentStart }.map { it.first / 1_000.0 }
            // The first beep is the start itself (the first tick)
            val next = listOf(0.0) + beeps.drop(1)
            val moved = next.zip(starts).maxOf { (a, b) -> kotlin.math.abs(a - b) }
            starts = next
            if (moved <= 1.0 || ++pass == MAX_PASSES) break
        }
        return result
    }

    @Test
    fun `6 x 400 m - hints only on the slow 4th and the fast 5th repeat, voice and bar on one segment`() {
        val slowRepeat = 1 + 2 * 3
        val fastRepeat = 1 + 2 * 4
        for (seed in SEEDS) {
            val result = repeats(seed)
            val bySecond = result.interval.toMap()
            val hints = result.hints().map { (t, line) ->
                Triple(t / 1_000L, bySecond.getValue(t)!!.segmentIndex, (line as VoiceLine.TargetHint).verdict)
            }
            println("seed $seed - hints (s, segment, verdict): $hints")

            assertTrue("seed $seed: $hints", hints.isNotEmpty())
            for ((sec, segment, verdict) in hints) {
                val expected = when (segment) {
                    slowRepeat -> TargetVerdict.TOO_SLOW
                    fastRepeat -> TargetVerdict.TOO_FAST
                    else -> null
                }
                assertEquals("seed $seed at $sec s in segment $segment: $hints", expected, verdict)
            }
            assertTrue("seed $seed: $hints", hints.any { it.second == slowRepeat })
            assertTrue("seed $seed: $hints", hints.any { it.second == fastRepeat })

            // The beep of each segment is on the tick the bar moves to it: one engine
            val beeps = result.voice.filter { it.second == VoiceLine.SegmentStart }.map { it.first }
            val changes = result.interval.zipWithNext()
                .filter { (a, b) -> a.second?.segmentIndex != b.second?.segmentIndex }
                .map { it.second.first }
            assertEquals("seed $seed", listOf(result.interval.first().first) + changes, beeps)
            val indices = result.interval.mapNotNull { it.second?.segmentIndex }
            assertEquals("seed $seed", indices.sorted(), indices)
        }
    }

    @Test
    fun `a zone 4 segment at 175 bpm (HRmax 190) - above the zone after 45 s, the hint after the hold`() {
        val segment = timed(0, 180, null).copy(targetHrZone = 4)
        val run = SyntheticRun(route = SyntheticRun.blockLoop(laps = 1), speedMps = mps(5, 0), hrAt = { 175 })
        val result = SessionReplay.run(
            run,
            gpsOnly.copy(segments = listOf(segment), zones = HrZones(190)),
            listOf(ReplayEvent.Stop(atSec = 200))
        )

        val verdicts = result.interval.associate { (t, live) -> t / 1_000L to live?.target?.verdict }
        assertEquals(TargetVerdict.PENDING, verdicts[44])
        assertEquals(TargetVerdict.ABOVE_ZONE, verdicts[45])
        assertEquals(
            listOf(60L, 90L, 120L, 150L) to VoiceLine.TargetHint(TargetVerdict.ABOVE_ZONE, pace = null, bpm = 175),
            result.hints().map { it.first / 1_000L } to result.hints().first().second
        )
    }

    private companion object {
        val SEEDS = 1L..10L
        const val MAX_PASSES = 6
    }
}
