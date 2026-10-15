package com.runner.academy.voice

import com.runner.academy.data.LivePace
import com.runner.academy.data.SegmentTarget
import com.runner.academy.sensor.HeartRateDisplay
import com.runner.academy.util.HrZones
import com.runner.academy.voice.TargetVerdict.ABOVE_ZONE
import com.runner.academy.voice.TargetVerdict.IN
import com.runner.academy.voice.TargetVerdict.NO_DATA
import com.runner.academy.voice.TargetVerdict.NO_TARGET
import com.runner.academy.voice.TargetVerdict.PENDING
import com.runner.academy.voice.TargetVerdict.TOO_FAST
import com.runner.academy.voice.TargetVerdict.TOO_SLOW
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Architecture §04: the verdict, the hysteresis, the 10 s hold, the 30 s cooldown and the segment's end. */
class TargetCheckTest {

    private val check = TargetCheck()
    private val pace430 = SegmentTarget.Pace(4.5f)
    private val zone4 = SegmentTarget.HrZone(4)
    private val zones190 = HrZones(190)

    private fun pace(min: Int, sec: Int, approximate: Boolean = false) = LivePace(min + sec / 60f, approximate)

    private fun fresh(bpm: Int) = HeartRateDisplay(bpm, HeartRateDisplay.Freshness.FRESH, HeartRateDisplay.LinkIcon.CONNECTED)
    private fun stale(bpm: Int) = HeartRateDisplay(bpm, HeartRateDisplay.Freshness.STALE, HeartRateDisplay.LinkIcon.CONNECTED)

    /** One tick at second [sec] of a segment that started at 0; the hint, if any, is taken as spoken. */
    private fun tick(
        sec: Int,
        target: SegmentTarget = pace430,
        pace: LivePace? = null,
        heartRate: HeartRateDisplay? = null,
        zones: HrZones? = zones190,
        segmentIndex: Int = 0,
        segmentStartSec: Int = 0,
        remainingMs: Long? = 600_000L,
        spoken: Boolean = true
    ): TargetCheck.Result {
        val result = check.onTick(
            segmentIndex = segmentIndex,
            segmentElapsedMs = (sec - segmentStartSec) * 1_000L,
            segmentRemainingMs = remainingMs,
            target = target,
            pace = pace,
            heartRate = heartRate,
            zones = zones,
            toleranceSec = 10,
            nowMs = sec * 1_000L
        )
        if (spoken && result.hint != null) check.hintGiven(sec * 1_000L)
        return result
    }

    /** Ticks [from]..[to] with the pace of each second; the seconds a hint came due. */
    private fun hintSeconds(from: Int, to: Int, paceAt: (Int) -> LivePace?): List<Int> =
        (from..to).filter { sec -> tick(sec, pace = paceAt(sec)).hint != null }

    @Test
    fun `4 45 for a 4 30 target - too slow from 15 s, the hint at 25 s, the next not before 55 s`() {
        val steady = { sec: Int -> if (sec < 15) null else pace(4, 45) }

        assertEquals(PENDING, tick(1).state.verdict)
        assertEquals(PENDING, tick(14).state.verdict)
        val at15 = tick(15, pace = steady(15))
        assertEquals(TargetState(TOO_SLOW, approximate = false, value = 4.75f), at15.state)
        assertNull(at15.hint)

        val hints = hintSeconds(16, 90, steady)
        assertEquals(listOf(25, 55, 85), hints)
        assertEquals(VoiceLine.TargetHint(TOO_SLOW, pace = 4.75f, bpm = null), tick(115, pace = pace(4, 45)).hint)
    }

    @Test
    fun `a pace swinging 4 38 to 4 43 - never a hint (hysteresis)`() {
        val swinging = { sec: Int -> pace(4, 38 + (sec % 6)) }

        assertEquals(emptyList<Int>(), hintSeconds(15, 300, swinging))
        assertEquals(IN, tick(301, pace = pace(4, 43)).state.verdict)
    }

    @Test
    fun `out of the band but within the hysteresis keeps the verdict it had`() {
        tick(15, pace = pace(4, 45))
        assertEquals(TOO_SLOW, tick(16, pace = pace(4, 42)).state.verdict)
        assertEquals(IN, tick(17, pace = pace(4, 40)).state.verdict)
        assertEquals(IN, tick(18, pace = pace(4, 42)).state.verdict)
    }

    @Test
    fun `back to 4 32 - in target and silent, 4 45 again - the hint not before 30 s after the last`() {
        assertEquals(listOf(25), hintSeconds(15, 30) { pace(4, 45) })
        assertEquals(emptyList<Int>(), hintSeconds(31, 40) { pace(4, 32) })
        assertEquals(IN, tick(41, pace = pace(4, 32)).state.verdict)

        // Off again from 42: held 10 s by 52, but the last hint was at 25
        assertEquals(listOf(55), hintSeconds(42, 60) { pace(4, 45) })
    }

    @Test
    fun `too fast says slow down with the pace`() {
        assertEquals(listOf(25), hintSeconds(15, 30) { pace(4, 10) })
        assertEquals(TOO_FAST, tick(31, pace = pace(4, 10), spoken = false).state.verdict)
    }

    @Test
    fun `a hint not given (speech busy) does not spend the cooldown - it comes on the next tick`() {
        (15..24).forEach { tick(it, pace = pace(4, 45)) }
        val due = tick(25, pace = pace(4, 45), spoken = false)
        assertTrue(due.hint != null)
        assertTrue(tick(26, pace = pace(4, 45)).hint != null)
        assertNull(tick(27, pace = pace(4, 45)).hint)
    }

    @Test
    fun `zone 4 at HRmax 190 with 175 - pending for 45 s, then above the zone, the hint 10 s later`() {
        assertEquals(PENDING, tick(44, target = zone4, heartRate = fresh(175)).state.verdict)
        assertEquals(TargetState(ABOVE_ZONE, approximate = false, value = 175f), tick(45, target = zone4, heartRate = fresh(175)).state)

        val hints = (46..60).filter { tick(it, target = zone4, heartRate = fresh(175)).hint != null }
        assertEquals(listOf(55), hints)
        assertEquals(
            VoiceLine.TargetHint(ABOVE_ZONE, pace = null, bpm = 175),
            tick(85, target = zone4, heartRate = fresh(175)).hint
        )
    }

    @Test
    fun `zone - a stale heart rate is no data and silent, zones off too`() {
        val stale = (46..120).map { tick(it, target = zone4, heartRate = stale(175)) }
        assertTrue(stale.all { it.state.verdict == NO_DATA && it.hint == null })

        assertEquals(NO_DATA, tick(121, target = zone4, heartRate = fresh(175), zones = null).state.verdict)
    }

    @Test
    fun `zone - within 2 bpm past the bound keeps the verdict, inside the zone is in`() {
        assertEquals(IN, tick(45, target = zone4, heartRate = fresh(172)).state.verdict)
        assertEquals(ABOVE_ZONE, tick(46, target = zone4, heartRate = fresh(173)).state.verdict)
        assertEquals(ABOVE_ZONE, tick(47, target = zone4, heartRate = fresh(171)).state.verdict)
        assertEquals(IN, tick(48, target = zone4, heartRate = fresh(170)).state.verdict)
        assertEquals(TargetVerdict.BELOW_ZONE, tick(49, target = zone4, heartRate = fresh(149)).state.verdict)
    }

    @Test
    fun `a steps pace - the indicator says too slow approximately, the voice stays silent`() {
        val approx = (15..120).map { tick(it, pace = pace(4, 45, approximate = true)) }

        assertTrue(approx.all { it.state == TargetState(TOO_SLOW, approximate = true, value = 4.75f) })
        assertTrue(approx.all { it.hint == null })
    }

    @Test
    fun `no pace after the first 15 s is no data and starts the hold over`() {
        (15..23).forEach { tick(it, pace = pace(4, 45)) }
        assertEquals(NO_DATA, tick(24, pace = null).state.verdict)
        assertEquals(listOf(35), hintSeconds(25, 40) { pace(4, 45) })
    }

    @Test
    fun `the last 10 s of a segment - no hint`() {
        val hints = (15..60).filter { sec ->
            tick(sec, pace = pace(4, 45), remainingMs = (60 - sec) * 1_000L + 10_000L - 25_000L).hint != null
        }
        // Remaining ≤ 10 s from second 35 on; the hold ends at 25 (remaining 20 s)
        assertEquals(listOf(25), hints)
        assertNull(tick(61, pace = pace(4, 45), remainingMs = 10_000L).hint)
    }

    @Test
    fun `no target or a completed workout - no verdict, never a hint`() {
        val none = (1..120).map { tick(it, target = SegmentTarget.None, pace = pace(4, 45)) }
        assertTrue(none.all { it.state.verdict == NO_TARGET && it.hint == null && it.state.value == null })
    }

    @Test
    fun `a new segment starts the hold over and keeps the cooldown`() {
        assertEquals(listOf(25), hintSeconds(15, 40) { pace(4, 45) })

        // Segment 1 from 41: pending 15 s, too slow from 56, held by 66 (the cooldown ended at 55)
        val hints = (41..80).filter { sec ->
            val p = if (sec < 56) null else pace(4, 45)
            tick(sec, pace = p, segmentIndex = 1, segmentStartSec = 41).hint != null
        }
        assertEquals(listOf(66), hints)
        assertEquals(PENDING, TargetCheck().onTick(2, 1_000L, null, pace430, null, null, null, 10, 0L).state.verdict)
    }
}
