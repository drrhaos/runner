package com.runner.academy.gpsreplay

import com.runner.academy.data.SegmentGoalType
import com.runner.academy.data.SegmentKind
import com.runner.academy.data.WorkoutTemplateSegment
import com.runner.academy.voice.SummaryPart
import com.runner.academy.voice.VoiceLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

/**
 * The voice on the bench: the service's coach on the 1 s timer over a synthetic run — the
 * kilometre summaries on time, the segments by the clock whatever the GPS does, no summary for
 * the past after a restore, and nothing over a manual pause.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class VoiceReplayTest {

    /**
     * 5:00/km on the block loop (1 300 m a lap), without GPS noise: the live distance is the true
     * one, so the timing of the voice is checked, not the filter (noise adds ~0.5 %).
     */
    private fun steady(laps: Int, gapSec: IntRange? = null) = SyntheticRun(
        route = SyntheticRun.blockLoop(laps), speedMps = 1_000.0 / 300.0, gapSec = gapSec, noiseInnovationM = 0.0
    )

    private fun summarySeconds(result: SessionReplayResult) =
        result.voice.filter { it.second is VoiceLine.Summary }.map { (it.first / 1_000L).toInt() }

    private fun timed(sortOrder: Int, sec: Int, kind: SegmentKind) = WorkoutTemplateSegment(
        templateId = 1L, sortOrder = sortOrder, kind = kind, title = "S$sortOrder",
        goalType = SegmentGoalType.DURATION, durationMs = sec * 1_000L
    )

    @Test
    fun `10 km at an even pace - ten summaries, each within a second of its kilometre`() {
        val result = SessionReplay.run(steady(laps = 8), events = listOf(ReplayEvent.Stop(atSec = 3_010)))

        val seconds = summarySeconds(result)
        assertEquals(10, seconds.size)
        val summaries = result.voice.map { it.second as VoiceLine.Summary }
        seconds.zip(summaries).forEachIndexed { i, (sec, summary) ->
            val km = i + 1
            // Within a second of the live distance reaching the kilometre: one tick at 3.3 m/s
            val said = (summary.parts[0] as SummaryPart.Distance).meters
            assertTrue("km $km said at $said m", said >= km * 1_000f && said < km * 1_000f + 3.5f)
            // And within a few seconds of the truth (the filter's own error)
            assertTrue("km $km at $sec s", abs(sec - 300 * km) <= 3)
        }
        // The time said is the moving time of the tick it was said on
        val tenth = summaries.last().parts[1] as SummaryPart.Time
        assertEquals(seconds.last() * 1_000L, tenth.movingMs)
    }

    @Test
    fun `a 2 min segment through 90 s of GPS silence without steps - warning at 90 s, beep at 120 s`() {
        val segments = listOf(timed(0, 120, SegmentKind.WORK), timed(1, 120, SegmentKind.RECOVERY))
        val result = SessionReplay.run(
            steady(laps = 1, gapSec = 30..119),
            ReplaySettings(stepsAvailable = false, segments = segments),
            listOf(ReplayEvent.Stop(atSec = 200))
        )

        assertEquals(
            listOf(1_000L to "beep", 90_000L to "next S1", 120_000L to "beep"),
            result.voice.map { (t, line) -> t to describe(line) }
        )
    }

    @Test
    fun `a restore at 3,4 km speaks nothing until 4,0 km`() {
        val result = SessionReplay.run(
            steady(laps = 4),
            events = listOf(ReplayEvent.KillAndRestore(atSec = 1_020), ReplayEvent.Stop(atSec = 1_250))
        )

        val seconds = summarySeconds(result)
        assertEquals(4, seconds.size)
        assertTrue("$seconds", seconds.last() in 1_199..1_201)
    }

    @Test
    fun `a restore mid-interval neither beeps nor warns again`() {
        val segments = listOf(timed(0, 120, SegmentKind.WORK), timed(1, 120, SegmentKind.RECOVERY))
        val result = SessionReplay.run(
            steady(laps = 1),
            ReplaySettings(segments = segments),
            listOf(ReplayEvent.KillAndRestore(atSec = 100, downSec = 5), ReplayEvent.Stop(atSec = 200))
        )

        assertEquals(listOf("beep", "next S1", "beep"), result.voice.map { describe(it.second) })
    }

    @Test
    fun `a manual pause of 2 min - silent, the segments stand still`() {
        val segments = listOf(timed(0, 120, SegmentKind.WORK), timed(1, 120, SegmentKind.RECOVERY))
        val result = SessionReplay.run(
            steady(laps = 1),
            ReplaySettings(segments = segments),
            listOf(ReplayEvent.ManualPause(atSec = 60, sec = 120), ReplayEvent.Stop(atSec = 300))
        )

        assertEquals(
            listOf(1_000L to "beep", 210_000L to "next S1", 240_000L to "beep"),
            result.voice.map { (t, line) -> t to describe(line) }
        )
    }

    private fun describe(line: VoiceLine): String = when (line) {
        VoiceLine.SegmentStart -> "beep"
        is VoiceLine.SegmentUpcoming -> "next ${line.segment.title}"
        else -> line.toString()
    }
}
