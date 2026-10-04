package com.runner.academy.util

import android.location.Location
import com.runner.academy.data.WorkoutType
import com.runner.academy.util.TrackFilter.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.cos

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TrackFilterTest {

    private val filter = TrackFilter(WorkoutType.EASY_RUN)

    /** A fix [eastM]/[northM] metres from a fixed origin, [sec] seconds into the run. */
    private fun fix(
        eastM: Double,
        northM: Double,
        sec: Int,
        speed: Float = 3f,
        accuracy: Float = 6f
    ): Location = Location("test").apply {
        latitude = LAT0 + northM / M_PER_DEG
        longitude = LON0 + eastM / (M_PER_DEG * cos(Math.toRadians(LAT0)))
        time = T0 + sec * 1_000L
        this.accuracy = accuracy
        this.speed = speed
        altitude = 150.0
    }

    /** Runs east at 3 m/s from [fromSec] to [toSec]. */
    private fun runEast(fromSec: Int, toSec: Int) {
        for (sec in fromSec..toSec) {
            assertTrue("fix at $sec s", filter.process(fix(sec * 3.0, 0.0, sec)) is Verdict.Accepted)
        }
    }

    private fun Verdict.rejected() = this as Verdict.Rejected
    private fun Verdict.accepted() = this as Verdict.Accepted

    @Test
    fun `repeated bit-identical fixes are a false signal`() {
        runEast(0, 10)
        val frozen = fix(33.0, 0.0, 11)
        assertTrue(filter.process(frozen) is Verdict.Accepted)
        assertTrue(filter.process(Location(frozen).apply { time += 1_000 }) is Verdict.NearDuplicate)

        val third = filter.process(Location(frozen).apply { time += 2_000 }).rejected()

        assertEquals(TrackFilter.Reason.FROZEN, third.reason)
        assertFalse(third.retractStart)
        assertTrue(filter.inFalseSignal)
    }

    @Test
    fun `standing still with receiver jitter is not frozen`() {
        runEast(0, 10)
        for (sec in 11..60) {
            val jitter = if (sec % 2 == 0) 0.3 else -0.3
            assertTrue(filter.process(fix(30.0 + jitter, 0.0, sec, speed = 0f)) is Verdict.NearDuplicate)
        }
        assertFalse(filter.inFalseSignal)
    }

    @Test
    fun `a dropped stretch that ends with a good fix is bridged by a straight line`() {
        runEast(0, 10)
        val frozen = fix(33.0, 0.0, 11)
        filter.process(frozen)
        for (sec in 12..60) filter.process(Location(frozen).apply { time = T0 + sec * 1_000L })

        val back = filter.process(fix(183.0, 40.0, 61)).accepted()

        assertTrue(back.afterGap)
        val straight = fix(183.0, 40.0, 61).distanceTo(frozen)
        assertEquals(straight, back.bridgeMeters!!, 0.5f)
        assertEquals(straight, back.segmentDistanceMeters, 0.5f)
        assertFalse(filter.inFalseSignal)
    }

    @Test
    fun `a short dropped stretch is an ordinary step`() {
        runEast(0, 10)
        for (sec in 11..15) assertTrue(filter.process(fix(5_000.0, 0.0, sec)) is Verdict.Rejected)

        val back = filter.process(fix(48.0, 0.0, 16)).accepted()

        assertFalse(back.afterGap)
        assertNull(back.bridgeMeters)
        assertEquals(18f, back.segmentDistanceMeters, 0.5f)
    }

    @Test
    fun `a frozen start is retracted and the track begins at the first good fix`() {
        val airport = fix(15_000.0, 8_000.0, 0, speed = 0f)
        assertTrue(filter.process(airport) is Verdict.Accepted)
        assertTrue(filter.process(Location(airport).apply { time += 1_000 }) is Verdict.NearDuplicate)

        val third = filter.process(Location(airport).apply { time += 2_000 }).rejected()

        assertTrue(third.retractStart)
        assertNull(filter.anchor)
        assertTrue(filter.inFalseSignal)
        assertFalse(filter.process(Location(airport).apply { time += 3_000 }).rejected().retractStart)

        val first = filter.process(fix(0.0, 0.0, 4)).accepted()
        assertFalse(first.afterGap)
        assertEquals(0f, first.segmentDistanceMeters, 0.01f)
        assertFalse(filter.inFalseSignal)
    }

    @Test
    fun `an impossible jump after a silence is dropped`() {
        runEast(0, 10)

        // Two minutes without fixes, then ~13 km away
        val far = filter.process(fix(9_000.0, 9_500.0, 130, speed = 3f)).rejected()

        assertEquals(TrackFilter.Reason.IMPLAUSIBLE_JUMP, far.reason)
        assertTrue(filter.inFalseSignal)
    }

    @Test
    fun `a plausible resume after a silence is a plain gap without distance`() {
        runEast(0, 10)
        filter.process(fix(9_000.0, 9_500.0, 130))

        val back = filter.process(fix(400.0, 0.0, 131)).accepted()

        assertTrue(back.afterGap)
        assertNull(back.bridgeMeters)
        assertEquals(0f, back.segmentDistanceMeters, 0.01f)
    }

    @Test
    fun `a forced gap resume is checked for plausibility too`() {
        runEast(0, 10)

        val far = filter.process(fix(5_000.0, 0.0, 15), forceGapResume = true).rejected()

        assertEquals(TrackFilter.Reason.IMPLAUSIBLE_JUMP, far.reason)
    }

    @Test
    fun `reported speed far above the workout cap is a false signal`() {
        runEast(0, 10)

        val fast = filter.process(fix(33.0, 0.0, 11, speed = 28f)).rejected()

        assertEquals(TrackFilter.Reason.REPORTED_SPEED, fast.reason)
        assertTrue(filter.inFalseSignal)
        assertTrue(filter.process(fix(36.0, 0.0, 12, speed = 12f)) is Verdict.Accepted)
    }

    @Test
    fun `a single outlier is not a false-signal episode`() {
        runEast(0, 10)

        assertTrue(filter.process(fix(333.0, 0.0, 11)) is Verdict.Rejected)

        assertFalse(filter.inFalseSignal)
    }

    @Test
    fun `reset with an anchor continues the run`() {
        val anchor = fix(0.0, 0.0, 0)
        filter.reset(anchor)

        val next = filter.process(fix(3.0, 0.0, 1)).accepted()

        assertNotNull(filter.anchor)
        assertFalse(next.afterGap)
        assertEquals(3f, next.segmentDistanceMeters, 0.2f)
    }

    // --- Silence counted by steps (owner's decision 2026-10-04) ---

    /** 1 m per step, so steps equal metres run. */
    private val stepped = TrackFilter(WorkoutType.EASY_RUN, StepDistanceEstimator { steps, _ -> steps.toFloat() })

    /** Runs east at 3 m/s with steps from [fromSec] to [toSec] through [stepped]. */
    private fun runEastWithSteps(fromSec: Int, toSec: Int) {
        for (sec in fromSec..toSec) {
            assertTrue("fix at $sec s", stepped.process(fix(sec * 3.0, 0.0, sec), steps = sec * 3) is Verdict.Accepted)
        }
    }

    @Test
    fun `a silence with steps is bridged by steps when GPS returns`() {
        runEastWithSteps(0, 10)
        // 50 s without any fix; the runner ran 150 m but came back close to where GPS stopped
        val back = stepped.process(fix(60.0, 0.0, 61), steps = 183).accepted()

        assertTrue(back.afterGap)
        assertEquals(153f, back.bridgeMeters!!, 0.5f)
        assertTrue(back.bridgeFromSteps)
        assertEquals(153f, back.segmentDistanceMeters, 0.5f)
    }

    @Test
    fun `a silence with steps takes the straight line when it is longer`() {
        runEastWithSteps(0, 10)
        val back = stepped.process(fix(400.0, 0.0, 61), steps = 130).accepted()

        assertEquals(370f, back.bridgeMeters!!, 1f)
        assertFalse(back.bridgeFromSteps)
    }

    @Test
    fun `steps are counted live during a silence and the closing fix only adds the rest`() {
        runEastWithSteps(0, 10)
        val before = stepped.countedMeters

        // Less than 20 s without a fix: not a silence yet
        stepped.countSilence(nowMs = T0 + 25_000L, steps = 75, cadence = 170f)
        assertEquals(before, stepped.countedMeters, 0.01f)

        stepped.countSilence(nowMs = T0 + 40_000L, steps = 120, cadence = 170f)
        assertEquals(before + 90f, stepped.countedMeters, 0.5f)

        val counted = stepped.countedMeters
        val back = stepped.process(fix(60.0, 0.0, 61), steps = 183).accepted()
        assertEquals(153f, back.bridgeMeters!!, 0.5f)
        assertEquals(before + 153f, stepped.countedMeters, 0.5f)
        assertTrue(stepped.countedMeters >= counted)
        assertEquals(0f, stepped.pendingMeters, 0.01f)
    }

    @Test
    fun `steps before the first fix count as a lead-in`() {
        stepped.countSilence(nowMs = T0 + 30_000L, steps = 90, cadence = 170f)
        assertEquals(90f, stepped.pendingMeters, 0.5f)

        val first = stepped.process(fix(0.0, 0.0, 31), steps = 93).accepted()
        assertEquals(93f, first.leadInMeters!!, 0.5f)
        assertEquals(93f, stepped.countedMeters, 0.5f)
    }

    @Test
    fun `without steps a silence counts nothing`() {
        runEast(0, 10)
        filter.countSilence(nowMs = T0 + 60_000L, steps = 150, cadence = 170f)
        assertEquals(0f, filter.pendingMeters, 0.01f)
    }

    @Test
    fun `a far fix after a silence with steps is still dropped`() {
        runEastWithSteps(0, 10)
        stepped.countSilence(nowMs = T0 + 120_000L, steps = 360, cadence = 170f)

        val far = stepped.process(fix(9_000.0, 9_500.0, 130), steps = 390).rejected()
        assertEquals(TrackFilter.Reason.IMPLAUSIBLE_JUMP, far.reason)
        // Counted live stays counted, and the dropped fix carries the steps on to its own time
        assertEquals(30f + 360f, stepped.countedMeters, 1f)
    }

    @Test
    fun `a pause is not a silence and its straight line is not counted`() {
        runEastWithSteps(0, 10)
        stepped.onResume()
        // Right after a long pause: the silence clock restarts at the resume
        stepped.countSilence(nowMs = T0 + 300_000L, steps = 30, cadence = 0f)
        stepped.countSilence(nowMs = T0 + 310_000L, steps = 45, cadence = 170f)
        assertEquals(0f, stepped.pendingMeters, 0.01f)

        // First fix after resume, 900 m away (drove while paused), 15 steps since resume
        val back = stepped.process(fix(930.0, 0.0, 312), steps = 45).accepted()
        assertTrue(back.afterGap)
        assertNull("no distance across the pause", back.bridgeMeters)
        assertEquals(30f, stepped.countedMeters, 0.5f)
    }

    @Test
    fun `after a pause a silence counts only the steps run after it`() {
        runEastWithSteps(0, 10)
        stepped.onResume()
        stepped.countSilence(nowMs = T0 + 300_000L, steps = 30, cadence = 170f)
        stepped.countSilence(nowMs = T0 + 330_000L, steps = 120, cadence = 170f)
        assertEquals(90f, stepped.pendingMeters, 0.5f)

        val back = stepped.process(fix(2_000.0, 0.0, 335), steps = 135).accepted()
        // Steps only: the straight line crosses the pause
        assertEquals(105f, back.bridgeMeters!!, 0.5f)
        assertTrue(back.bridgeFromSteps)
    }

    @Test
    fun `the silence clock runs on the fixes' monotonic time`() {
        val f = TrackFilter(WorkoutType.EASY_RUN, StepDistanceEstimator { steps, _ -> steps.toFloat() })
        val boot = 5_000L
        for (sec in 0..10) {
            val location = fix(sec * 3.0, 0.0, sec).apply { elapsedRealtimeNanos = (boot + sec * 1_000L) * 1_000_000L }
            f.process(location, steps = sec * 3)
        }
        // Wall time far ahead of the fixes' clock does not count as silence
        f.countSilence(nowMs = boot + 15_000L, steps = 45, cadence = 170f)
        assertEquals(0f, f.pendingMeters, 0.01f)
        f.countSilence(nowMs = boot + 40_000L, steps = 120, cadence = 170f)
        assertEquals(90f, f.pendingMeters, 0.5f)
    }

    @Test
    fun `a short step silence is neither shown nor bridged`() {
        runEastWithSteps(0, 10)
        stepped.countSilence(nowMs = T0 + 40_000L, steps = 50, cadence = 60f)
        assertEquals("20 m by steps: below a bridge", 0f, stepped.pendingMeters, 0.01f)

        val back = stepped.process(fix(40.0, 0.0, 41), steps = 50).accepted()
        assertTrue(back.afterGap)
        assertNull(back.bridgeMeters)
    }

    @Test
    fun `steps before the first fix are a lead-in without any tick`() {
        val first = stepped.process(fix(0.0, 0.0, 30), steps = 90).accepted()
        assertEquals(90f, first.leadInMeters!!, 0.5f)
    }

    @Test
    fun `a few steps before the first fix are no lead-in`() {
        val first = stepped.process(fix(0.0, 0.0, 5), steps = 8).accepted()
        assertNull(first.leadInMeters)
    }

    private companion object {
        const val LAT0 = 55.75
        const val LON0 = 37.6
        const val M_PER_DEG = 111_320.0
        const val T0 = 1_700_000_000_000L
    }
}
