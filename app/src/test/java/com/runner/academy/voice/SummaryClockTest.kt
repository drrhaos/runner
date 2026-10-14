package com.runner.academy.voice

import com.runner.academy.sensor.HeartRateDisplay
import com.runner.academy.sensor.HeartRateDisplay.Freshness
import com.runner.academy.sensor.HeartRateDisplay.LinkIcon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SummaryClockTest {

    private val clock = SummaryClock()
    private val kmProfile = VoiceSettings.DEFAULT.free
    private fun profile(every: SummaryEvery, vararg metrics: SummaryMetric) =
        kmProfile.copy(summary = every, metrics = metrics.toSet())

    private fun fresh(bpm: Int) = HeartRateDisplay(bpm, Freshness.FRESH, LinkIcon.CONNECTED)
    private fun stale(bpm: Int) = HeartRateDisplay(bpm, Freshness.STALE, LinkIcon.CONNECTED)
    private val none = HeartRateDisplay(null, Freshness.NONE, LinkIcon.LOST)

    /** One tick; the average pace as the session has it. */
    private fun tick(
        movingMs: Long,
        distanceM: Float,
        profile: VoiceProfile = kmProfile,
        autoPaused: Boolean = false,
        heartRate: HeartRateDisplay? = null
    ): VoiceLine.Summary? {
        val avgPace = if (distanceM > 0f) (movingMs / 60_000f) / (distanceM / 1000f) else 0f
        return clock.onTick(movingMs, distanceM, autoPaused, avgPace, heartRate, profile)
    }

    /** Runs at 5:00/km from [fromSec] to [toSec]; returns (second, summary) of each summary. */
    private fun runSteady(
        toSec: Int,
        fromSec: Int = 1,
        profile: VoiceProfile = kmProfile,
        heartRateAt: (Int) -> HeartRateDisplay? = { null }
    ): List<Pair<Int, VoiceLine.Summary>> = (fromSec..toSec).mapNotNull { sec ->
        tick(sec * 1_000L, sec * 10f / 3f, profile, heartRate = heartRateAt(sec))?.let { sec to it }
    }

    @Test
    fun `the kilometre summary speaks once per kilometre with distance, time and average pace`() {
        // 5:00/km: the kilometres fall on 300 s and 600 s
        val said = runSteady(toSec = 650)

        assertEquals(listOf(300, 600), said.map { it.first })
        val first = said.first().second.parts
        assertEquals(3, first.size)
        assertEquals(1_000f, (first[0] as SummaryPart.Distance).meters, 0.5f)
        assertEquals(SummaryPart.Time(300_000L), first[1])
        assertEquals(5f, (first[2] as SummaryPart.AvgPace).minPerKm, 0.01f)
    }

    @Test
    fun `a resume at 3,4 km stays silent until 4,0 km`() {
        clock.resume(movingMs = 1_020_000L, distanceM = 3_400f)

        // 600 m more at 5:00/km: 180 s
        val said = (1..200).mapNotNull { s ->
            tick(1_020_000L + s * 1_000L, 3_400f + s * 10f / 3f)?.let { s }
        }
        assertEquals(listOf(180), said)
    }

    @Test
    fun `a time summary follows the moving clock, not the auto-pause`() {
        val every5 = profile(SummaryEvery.MIN_5, SummaryMetric.TIME)
        var moving = 0L
        val said = mutableListOf<Int>()
        for (sec in 1..400) {
            val autoPaused = sec in 100..159
            if (!autoPaused) moving += 1_000L
            if (clock.onTick(moving, sec * 3f, autoPaused, 5f, null, every5) != null) said += sec
        }
        // 300 s of moving reached at 360 s of the clock
        assertEquals(listOf(360), said)
    }

    @Test
    fun `a summary due in an auto-pause waits for the run to go on`() {
        tick(299_000L, 999f)
        assertNull(tick(300_000L, 1_001f, autoPaused = true))
        assertNotNull(tick(300_000L, 1_002f))
    }

    @Test
    fun `a jump over two marks speaks once and the next mark is after it`() {
        val half = profile(SummaryEvery.KM_0_5, SummaryMetric.DISTANCE)
        tick(100_000L, 400f, half)
        assertEquals(listOf(SummaryPart.Distance(1_050f)), tick(101_000L, 1_050f, half)?.parts)
        assertNull(tick(102_000L, 1_400f, half))
        assertEquals(listOf(SummaryPart.Distance(1_500f)), tick(103_000L, 1_500f, half)?.parts)
    }

    @Test
    fun `the split pace is the pace since the previous summary`() {
        val split = profile(SummaryEvery.KM_1, SummaryMetric.SPLIT_PACE, SummaryMetric.AVG_PACE)
        // The first kilometre in 300 s, the second in 240 s
        tick(1_000L, 3f, split)
        val first = tick(300_000L, 1_000f, split)!!
        val second = tick(540_000L, 2_000f, split)!!

        assertEquals(5f, (first.parts[0] as SummaryPart.AvgPace).minPerKm, 0.01f)
        assertEquals(5f, (first.parts[1] as SummaryPart.SplitPace).minPerKm, 0.01f)
        assertEquals(4.5f, (second.parts[0] as SummaryPart.AvgPace).minPerKm, 0.01f)
        assertEquals(4f, (second.parts[1] as SummaryPart.SplitPace).minPerKm, 0.01f)
        assertEquals(km1, (first.parts[1] as SummaryPart.SplitPace).span)
        assertEquals(km1, (second.parts[1] as SummaryPart.SplitPace).span)
    }

    private val km1 = SplitSpan.Every(SummaryEvery.KM_1)
    private fun VoiceLine.Summary?.span() = (this!!.parts.single() as SummaryPart.SplitPace).span

    @Test
    fun `the split after a resume or an interval change is partial, the next one whole again`() {
        val split = profile(SummaryEvery.KM_1, SummaryMetric.SPLIT_PACE)
        clock.resume(movingMs = 1_020_000L, distanceM = 3_400f)
        val afterResume = tick(1_200_000L, 4_000f, split)!!
        assertEquals(SplitSpan.SinceLastSummary, afterResume.span())
        assertEquals(5f, (afterResume.parts.single() as SummaryPart.SplitPace).minPerKm, 0.01f)
        assertEquals(km1, tick(1_500_000L, 5_000f, split).span())

        val every2 = profile(SummaryEvery.KM_2, SummaryMetric.SPLIT_PACE)
        tick(1_650_000L, 5_500f, every2)
        assertEquals(SplitSpan.SinceLastSummary, tick(1_800_000L, 6_000f, every2).span())
        assertEquals(SplitSpan.Every(SummaryEvery.KM_2), tick(2_400_000L, 8_000f, every2).span())
    }

    @Test
    fun `a summary switched on mid-run before any summary covers the run so far`() {
        val off = profile(SummaryEvery.OFF, SummaryMetric.SPLIT_PACE)
        runSteady(toSec = 1_020, profile = off) // 3 400 m, silent
        val split = profile(SummaryEvery.KM_1, SummaryMetric.SPLIT_PACE)

        val first = runSteady(fromSec = 1_021, toSec = 1_200, profile = split).single().second
        // Since the start: the pace of the whole 4 km, not "the last kilometre"
        assertEquals(SplitSpan.SinceStart, first.span())
        assertEquals(5f, (first.parts.single() as SummaryPart.SplitPace).minPerKm, 0.01f)
        assertEquals(km1, runSteady(fromSec = 1_201, toSec = 1_500, profile = split).single().second.span())
    }

    @Test
    fun `the split heart rate averages fresh values, skipped when they cover under half the ticks`() {
        val hr = profile(SummaryEvery.MIN_1, SummaryMetric.SPLIT_HR, SummaryMetric.CURRENT_HR)
        // First minute: fresh on 40 % of the ticks
        val first = runSteady(toSec = 60, profile = hr) { sec -> if (sec % 5 in 1..2) fresh(150) else none }
        assertEquals(listOf(60), first.map { it.first })
        assertEquals(emptyList<SummaryPart>(), first.single().second.parts)

        // Second minute: fresh on 60 %, 140 and 160 alternating, stale at the summary
        val second = runSteady(fromSec = 61, toSec = 120, profile = hr) { sec ->
            when {
                sec == 120 -> stale(170)
                sec % 5 < 3 -> fresh(if (sec % 2 == 0) 140 else 160)
                else -> none
            }
        }
        val parts = second.single().second.parts
        assertEquals(2, parts.size)
        assertEquals(150f, (parts[0] as SummaryPart.SplitHr).bpm.toFloat(), 3f)
        assertEquals(SummaryPart.CurrentHr(170), parts[1])
    }

    @Test
    fun `no heart rate - the heart-rate parts are left out`() {
        val hr = profile(SummaryEvery.MIN_1, SummaryMetric.TIME, SummaryMetric.SPLIT_HR, SummaryMetric.CURRENT_HR)
        val said = runSteady(toSec = 60, profile = hr) { none }

        assertEquals(listOf(SummaryPart.Time(60_000L)), said.single().second.parts)
    }

    @Test
    fun `an interval changed mid-run sets the next mark after the current position`() {
        runSteady(toSec = 450) // 1 500 m, one summary at 1 km
        val half = profile(SummaryEvery.KM_0_5, SummaryMetric.DISTANCE)

        val said = runSteady(fromSec = 451, toSec = 750, profile = half)
        // Not at once at 1 500 m: at 2 000 and 2 500 m
        assertEquals(listOf(600, 750), said.map { it.first })
    }

    @Test
    fun `off and an empty metric set never speak`() {
        assertEquals(emptyList<Pair<Int, VoiceLine.Summary>>(), runSteady(toSec = 700, profile = profile(SummaryEvery.OFF, SummaryMetric.TIME)))
        assertEquals(emptyList<Pair<Int, VoiceLine.Summary>>(), runSteady(fromSec = 701, toSec = 1_400, profile = profile(SummaryEvery.KM_1)))
    }
}
