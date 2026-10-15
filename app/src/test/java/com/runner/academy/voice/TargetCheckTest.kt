package com.runner.academy.voice

import com.runner.academy.data.LivePace
import com.runner.academy.data.SegmentTarget
import com.runner.academy.sensor.HeartRateDisplay
import com.runner.academy.util.HrZones
import com.runner.academy.voice.TargetVerdict.ABOVE_ZONE
import com.runner.academy.voice.TargetVerdict.BELOW_ZONE
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

/**
 * Architecture §04: the verdict, the hysteresis, the hold, the 30 s cooldown and the segment's
 * end. The hold is 15 s and the hysteresis 5 s per km, as tuned on the bench (`TargetReplayTest`):
 * with the planned 10 s and 3 s the cases read "the hint 10 s after the verdict".
 */
class TargetCheckTest {

    private val check = TargetCheck()
    private val pace430 = SegmentTarget.Pace(4.5f)
    private val zone4 = SegmentTarget.HrZone(4)
    private val zones190 = HrZones(190)

    private fun pace(min: Int, sec: Int, approximate: Boolean = false) = LivePace(min + sec / 60f, approximate)

    /** 18 s per km slower than 4:30: past the band (10) and the hysteresis (5). */
    private val slow = pace(4, 48)

    private fun fresh(bpm: Int) = HeartRateDisplay(bpm, HeartRateDisplay.Freshness.FRESH, HeartRateDisplay.LinkIcon.CONNECTED)
    private fun stale(bpm: Int) = HeartRateDisplay(bpm, HeartRateDisplay.Freshness.STALE, HeartRateDisplay.LinkIcon.CONNECTED)

    /** One tick at second [sec] of a segment that started at [segmentStartSec]; a hint, if any, is taken as given. */
    private fun tick(
        sec: Int,
        target: SegmentTarget = pace430,
        pace: LivePace? = null,
        heartRate: HeartRateDisplay? = null,
        zones: HrZones? = zones190,
        segmentIndex: Int = 0,
        segmentStartSec: Int = 0,
        remainingMs: Long? = 600_000L,
        given: Boolean = true
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
        if (given && result.hint != null) check.hintGiven(sec * 1_000L)
        return result
    }

    /** Ticks [from]..[to] with the pace of each second; the seconds a hint came due. */
    private fun hintSeconds(from: Int, to: Int, paceAt: (Int) -> LivePace?): List<Int> =
        (from..to).filter { sec -> tick(sec, pace = paceAt(sec)).hint != null }

    @Test
    fun `4 48 for a 4 30 target - too slow from 15 s, the hint 15 s later, then every 30 s`() {
        val steady = { sec: Int -> if (sec < 15) null else slow }

        assertEquals(PENDING, tick(1).state.verdict)
        assertEquals(PENDING, tick(14).state.verdict)
        val at15 = tick(15, pace = steady(15))
        assertEquals(TargetState(TOO_SLOW, approximate = false, value = 4.8f), at15.state)
        assertNull(at15.hint)

        assertEquals(listOf(30, 60, 90), hintSeconds(16, 90, steady))
        assertEquals(VoiceLine.TargetHint(TOO_SLOW, pace = 4.8f, bpm = null), tick(120, pace = slow).hint)
    }

    @Test
    fun `a pace swinging 4 38 to 4 45 - never a hint (hysteresis)`() {
        val swinging = { sec: Int -> pace(4, 38 + (sec % 8)) }

        assertEquals(emptyList<Int>(), hintSeconds(15, 300, swinging))
        assertEquals(IN, tick(301, pace = pace(4, 45)).state.verdict)
    }

    @Test
    fun `out of the band but within the hysteresis keeps the verdict it had`() {
        assertEquals(TOO_SLOW, tick(15, pace = slow).state.verdict)
        assertEquals(TOO_SLOW, tick(16, pace = pace(4, 44)).state.verdict)
        assertEquals(IN, tick(17, pace = pace(4, 40)).state.verdict)
        assertEquals(IN, tick(18, pace = pace(4, 44)).state.verdict)
        assertEquals(IN, tick(19, pace = pace(4, 16)).state.verdict)
        assertEquals(TOO_FAST, tick(20, pace = pace(4, 14)).state.verdict)
    }

    @Test
    fun `back to 4 32 - in the target and silent, off again - the hint not before 30 s after the last`() {
        assertEquals(listOf(30), hintSeconds(15, 35) { slow })
        assertEquals(emptyList<Int>(), hintSeconds(36, 37) { pace(4, 32) })
        assertEquals(IN, tick(38, pace = pace(4, 32)).state.verdict)

        // Off again from 39: held 15 s by 54, but the last hint was at 30
        assertEquals(listOf(60), hintSeconds(39, 70) { slow })
    }

    @Test
    fun `too fast says slow down`() {
        assertEquals(listOf(30), hintSeconds(15, 35) { pace(4, 10) })
        assertEquals(TOO_FAST, tick(36, pace = pace(4, 10), given = false).state.verdict)
    }

    @Test
    fun `a hint not given (speech busy) does not spend the cooldown - it comes on the next tick`() {
        (15..29).forEach { tick(it, pace = slow) }
        assertTrue(tick(30, pace = slow, given = false).hint != null)
        assertTrue(tick(31, pace = slow).hint != null)
        assertNull(tick(32, pace = slow).hint)
    }

    @Test
    fun `zone 4 at HRmax 190 with 175 - pending for 45 s, then above the zone, the hint after the hold`() {
        assertEquals(PENDING, tick(44, target = zone4, heartRate = fresh(175)).state.verdict)
        assertEquals(TargetState(ABOVE_ZONE, approximate = false, value = 175f), tick(45, target = zone4, heartRate = fresh(175)).state)

        val hints = (46..70).filter { tick(it, target = zone4, heartRate = fresh(175)).hint != null }
        assertEquals(listOf(60), hints)
        assertEquals(
            VoiceLine.TargetHint(ABOVE_ZONE, pace = null, bpm = 175),
            tick(90, target = zone4, heartRate = fresh(175)).hint
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
        assertEquals(BELOW_ZONE, tick(49, target = zone4, heartRate = fresh(149)).state.verdict)
    }

    @Test
    fun `a steps pace - the indicator says too slow approximately, the voice stays silent`() {
        val approx = (15..120).map { tick(it, pace = pace(4, 48, approximate = true)) }

        assertTrue(approx.all { it.state == TargetState(TOO_SLOW, approximate = true, value = 4.8f) })
        assertTrue(approx.all { it.hint == null })
    }

    @Test
    fun `no pace after the window should be full is no data and starts the hold over`() {
        (15..23).forEach { tick(it, pace = slow) }
        assertEquals(NO_DATA, tick(24, pace = null).state.verdict)
        assertEquals(listOf(40), hintSeconds(25, 45) { slow })
    }

    @Test
    fun `the last 10 s of a segment - no hint`() {
        // 15 s left at 30, when the hold ends; 10 s left from 35 on
        val hints = (15..60).filter { sec -> tick(sec, pace = slow, remainingMs = (45 - sec) * 1_000L).hint != null }
        assertEquals(listOf(30), hints)
        assertNull(tick(61, pace = slow, remainingMs = 10_000L).hint)
    }

    @Test
    fun `no target - no verdict, never a hint`() {
        val none = (1..120).map { tick(it, target = SegmentTarget.None, pace = slow) }
        assertTrue(none.all { it.state == TargetState.NONE && it.hint == null })
    }

    @Test
    fun `a new segment starts the hold over and keeps the cooldown`() {
        assertEquals(listOf(30), hintSeconds(15, 40) { slow })

        // Segment 1 from 41: pending for 15 s, too slow from 56, held by 71 (the cooldown ended at 60)
        val hints = (41..90).filter { sec ->
            val p = if (sec < 56) null else slow
            tick(sec, pace = p, segmentIndex = 1, segmentStartSec = 41).hint != null
        }
        assertEquals(listOf(71), hints)
        assertEquals(PENDING, TargetCheck().onTick(2, 1_000L, null, pace430, null, null, null, 10, 0L).state.verdict)
    }
}
