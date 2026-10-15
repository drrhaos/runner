package com.runner.academy.service

import com.runner.academy.data.GpsStatus
import com.runner.academy.data.LivePace
import com.runner.academy.util.StepDistanceEstimator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LivePaceTrackerTest {

    /** A stride of 1 m whatever the cadence; the cadence it got is remembered. */
    private var strideCadence: Float? = null
    private val stride = StepDistanceEstimator { steps, cadence ->
        strideCadence = cadence
        steps * STRIDE_M
    }
    private val tracker = LivePaceTracker(stride)

    /** One tick at moving second [sec]; the distance in metres, the fix [ageMs] old. */
    private fun tick(
        sec: Int,
        distanceM: Float,
        steps: Int? = null,
        fresh: Boolean = true,
        ageMs: Long = 0L,
        movingMs: Long = sec * 1_000L
    ) = tracker.onTick(movingMs, distanceM, steps, fresh, ageMs)

    /** Runs at [mps] by GPS and by steps (1 m stride) from [fromSec] to [toSec] inclusive. */
    private fun run(
        fromSec: Int,
        toSec: Int,
        mps: Float = FIVE_MIN_MPS,
        startM: Float = 0f,
        fresh: (Int) -> Boolean = { true },
        withSteps: Boolean = true
    ): Float {
        var meters = startM
        for (sec in fromSec..toSec) {
            if (sec > fromSec) meters += mps
            tick(sec, meters, steps = if (withSteps) meters.toInt() else null, fresh = fresh(sec))
        }
        return meters
    }

    private fun assertPace(expectedMin: Float, pace: LivePace?, approximate: Boolean, toleranceSec: Float = 1f) {
        assertNotNull("pace", pace)
        assertEquals("pace min/km", expectedMin, pace!!.minPerKm, toleranceSec / 60f)
        assertEquals("approximate", approximate, pace.approximate)
    }

    @Test
    fun `no pace before 15 s of window, then the GPS pace`() {
        run(0, 14)
        assertNull(tracker.pace())
        run(15, 15, startM = 15 * FIVE_MIN_MPS)
        assertPace(5f, tracker.pace(), approximate = false)
    }

    @Test
    fun `the window is the last 30 s`() {
        val atSixty = run(0, 60, mps = SIX_MIN_MPS)
        run(60, 90, mps = FOUR_MIN_MPS, startM = atSixty)
        assertPace(4f, tracker.pace(), approximate = false)
    }

    @Test
    fun `the verdict window of 60 s - its own span, the tile's stays 30 s`() {
        // 60 s at 6:00, then 60 s at 4:00: the last 60 s are 4:00, the last 90 s would not be
        val atSixty = run(0, 60, mps = SIX_MIN_MPS)
        val atNinety = run(60, 90, mps = FOUR_MIN_MPS, startM = atSixty)
        // At 90 s the 60 s window is half 6:00, half 4:00: 1 min over 83.3 + 125 m, 4:48
        assertPace(4.8f, tracker.pace(windowMs = 60_000L), approximate = false)
        assertPace(4f, tracker.pace(), approximate = false)
        run(90, 120, mps = FOUR_MIN_MPS, startM = atNinety)
        assertPace(4f, tracker.pace(windowMs = 60_000L), approximate = false)
    }

    @Test
    fun `the verdict window is cut at the segment start like the tile's`() {
        val atSixty = run(0, 60, mps = SIX_MIN_MPS)
        run(60, 74, mps = FOUR_MIN_MPS, startM = atSixty)
        assertNull(tracker.pace(sinceMovingMs = 60_000L, windowMs = 60_000L))
        run(75, 80, mps = FOUR_MIN_MPS, startM = atSixty + 15 * FOUR_MIN_MPS)
        assertPace(4f, tracker.pace(sinceMovingMs = 60_000L, windowMs = 60_000L), approximate = false)
    }

    @Test
    fun `a stale tick 45 s ago - the 60 s window goes by steps, the 30 s one by GPS`() {
        run(0, 70, fresh = { it != 25 })
        assertPace(5f, tracker.pace(windowMs = 60_000L), approximate = true, toleranceSec = STEPS_TOLERANCE_SEC)
        assertPace(5f, tracker.pace(), approximate = false)
    }

    @Test
    fun `the time since the last restart`() {
        assertEquals(0L, tracker.sinceRestartMs)
        run(0, 40)
        assertEquals(40_000L, tracker.sinceRestartMs)
        tracker.restart(atMovingMs = 40_000L)
        assertEquals(0L, tracker.sinceRestartMs)
        tick(43, 200f)
        assertEquals(3_000L, tracker.sinceRestartMs)
    }

    @Test
    fun `the ring keeps the window after a long run`() {
        run(0, 1_000)
        assertPace(5f, tracker.pace(), approximate = false)
    }

    @Test
    fun `under 10 m in the window is standing - no pace`() {
        run(0, 30, mps = 0.3f)
        assertNull(tracker.pace())
    }

    @Test
    fun `slower than 20 min per km is no pace`() {
        // 0,8 m/s: 20:50 per km
        run(0, 30, mps = 0.8f)
        assertNull(tracker.pace())
        tracker.restart()
        // 0,9 m/s: 18:31 per km
        run(0, 30, mps = 0.9f)
        assertPace(1_000f / 0.9f / 60f, tracker.pace(), approximate = false)
    }

    @Test
    fun `the GPS pace is measured to the time of the fix, not of the tick`() {
        // A fix every 3 s: the distance stands between fixes, the fix ages 0, 1, 2 s
        for (sec in 0..40) {
            val fixSec = sec - sec % 3
            tick(sec, fixSec * FIVE_MIN_MPS, ageMs = (sec - fixSec) * 1_000L)
            if (sec >= 20) assertPace(5f, tracker.pace(), approximate = false, toleranceSec = 0.5f)
        }
    }

    @Test
    fun `a stale fix in the window - the pace by steps`() {
        run(0, 40, fresh = { it < 30 })
        assertPace(5f, tracker.pace(), approximate = true, toleranceSec = STEPS_TOLERANCE_SEC)
    }

    @Test
    fun `the steps pace takes the window's mean cadence, like the bridges`() {
        // 3 steps a second for 10 s, then 2: 50 steps over 20 s
        var steps = 0
        for (sec in 0..20) {
            if (sec > 0) steps += if (sec <= 10) 3 else 2
            tick(sec, 0f, steps = steps, fresh = false)
        }
        assertNotNull(tracker.pace())
        assertEquals(150f, strideCadence!!, 0.01f)
    }

    @Test
    fun `a stale tick before the segment start does not force steps inside it`() {
        // Stale at 50, the segment starts at 55: its own window is all fresh GPS
        val meters = run(0, 60, fresh = { it != 50 })
        run(60, 75, startM = meters)
        assertPace(5f, tracker.pace(sinceMovingMs = 55_000L), approximate = false)
        assertPace(5f, tracker.pace(), approximate = true, toleranceSec = STEPS_TOLERANCE_SEC)
    }

    @Test
    fun `after a restart the first fix's age does not reach into the paused time`() {
        run(0, 60)
        // Auto-paused at moving 100 s, the run goes on from there; the first tick's fix is 4 s
        // old (taken standing at the same place)
        tracker.restart(atMovingMs = 100_000L)
        tick(101, 0f, ageMs = 4_000L)
        for (sec in 102..116) tick(sec, (sec - 100) * FIVE_MIN_MPS)
        assertPace(5f, tracker.pace(), approximate = false)
    }

    @Test
    fun `the GPS pace comes back only 30 s after the last stale tick, the bridge outside it`() {
        // Silence 30..59 by steps; at 60 the closing fix adds a 40 m bridge to the distance
        var meters = run(0, 29)
        for (sec in 30..59) {
            meters += FIVE_MIN_MPS
            tick(sec, 29 * FIVE_MIN_MPS, steps = meters.toInt(), fresh = false)
        }
        meters += FIVE_MIN_MPS
        val distance = meters + 40f
        tick(60, distance, steps = meters.toInt())
        var sessionM = distance
        for (sec in 61..120) {
            meters += FIVE_MIN_MPS
            sessionM += FIVE_MIN_MPS
            tick(sec, sessionM, steps = meters.toInt())
            // The last stale tick is 59: GPS from 90 on
            assertPace(5f, tracker.pace(), approximate = sec < 90, toleranceSec = STEPS_TOLERANCE_SEC)
        }
    }

    @Test
    fun `no steps and no fresh GPS - no pace`() {
        run(0, 40, fresh = { false }, withSteps = false)
        assertNull(tracker.pace())
    }

    @Test
    fun `steps missing at one end of the window - no pace`() {
        var meters = 0f
        for (sec in 0..40) {
            meters += FIVE_MIN_MPS
            tick(sec, meters, steps = if (sec < 20) null else meters.toInt(), fresh = false)
        }
        // The window 10..40 starts without steps
        assertNull(tracker.pace())
    }

    @Test
    fun `no stride model - no pace by steps`() {
        val plain = LivePaceTracker(stride = null)
        for (sec in 0..40) plain.onTick(sec * 1_000L, sec * FIVE_MIN_MPS, sec * 3, false, 0L)
        assertNull(plain.pace())
    }

    @Test
    fun `a restart starts the window over`() {
        val meters = run(0, 60)
        tracker.restart()
        run(61, 75, startM = meters)
        assertNull("14 s after the restart", tracker.pace())
        run(76, 76, startM = meters + 15 * FIVE_MIN_MPS)
        assertPace(5f, tracker.pace(), approximate = false)
    }

    @Test
    fun `a restart forgets the stale ticks before it`() {
        val meters = run(0, 40, fresh = { it > 30 })
        tracker.restart()
        run(41, 60, startM = meters)
        assertPace(5f, tracker.pace(), approximate = false)
    }

    @Test
    fun `a moving clock that goes back starts the window over`() {
        run(0, 60)
        // An auto-pause backdated by 10 s
        tick(50, 200f, movingMs = 50_000L)
        assertNull(tracker.pace())
    }

    @Test
    fun `a tick at the same moving time replaces the last one`() {
        run(0, 30)
        tick(30, 30 * FIVE_MIN_MPS + 50f)
        val pace = tracker.pace()!!
        assertTrue("the later distance counts: ${pace.minPerKm}", pace.minPerKm < 4.5f)
    }

    @Test
    fun `the window cut at the segment start - none for 15 s, then its own pace`() {
        val atSixty = run(0, 60, mps = SIX_MIN_MPS)
        val atSeventy = run(60, 70, mps = FOUR_MIN_MPS, startM = atSixty)
        assertNull("10 s into the segment", tracker.pace(sinceMovingMs = 60_000L))
        assertNotNull("the whole window has a pace", tracker.pace())
        run(70, 80, mps = FOUR_MIN_MPS, startM = atSeventy)
        assertPace(4f, tracker.pace(sinceMovingMs = 60_000L), approximate = false)
        val whole = tracker.pace()!!
        assertTrue("the 30 s window still has the 6:00: ${whole.minPerKm}", whole.minPerKm > 4.3f)
    }

    @Test
    fun `a GPS fix is fresh within 5 s or two request intervals, never in a false signal or a loss`() {
        fun fresh(ageMs: Long?, requestMs: Long, falseSignal: Boolean, status: GpsStatus) =
            LivePaceTracker.isGpsFresh(ageMs, requestMs, falseSignal, status)
        assertTrue(fresh(5_000L, 1_000L, false, GpsStatus.FOUND))
        assertFalse(fresh(5_001L, 1_000L, false, GpsStatus.FOUND))
        assertTrue(fresh(6_000L, 3_000L, false, GpsStatus.FOUND))
        assertFalse(fresh(6_001L, 3_000L, false, GpsStatus.FOUND))
        assertFalse("no fix yet", fresh(null, 1_000L, false, GpsStatus.SEARCHING))
        assertTrue(fresh(0L, 1_000L, false, GpsStatus.FOUND))
        assertFalse("the wall clock stepped back", fresh(-1L, 1_000L, false, GpsStatus.FOUND))
        assertFalse("false signal", fresh(1_000L, 1_000L, true, GpsStatus.FOUND))
        assertFalse("lost", fresh(1_000L, 1_000L, false, GpsStatus.LOST))
        assertFalse("unreliable", fresh(1_000L, 1_000L, false, GpsStatus.UNRELIABLE))
        assertTrue("weak is still GPS", fresh(1_000L, 1_000L, false, GpsStatus.WEAK))
    }

    private companion object {
        const val STRIDE_M = 1f
        /** Whole steps: one step either way in a 100-step window is 3 s per km. */
        const val STEPS_TOLERANCE_SEC = 4f
        const val FIVE_MIN_MPS = 1_000f / 300f
        const val FOUR_MIN_MPS = 1_000f / 240f
        const val SIX_MIN_MPS = 1_000f / 360f
    }
}
