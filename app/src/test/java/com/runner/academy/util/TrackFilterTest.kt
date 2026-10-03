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

    private companion object {
        const val LAT0 = 55.75
        const val LON0 = 37.6
        const val M_PER_DEG = 111_320.0
        const val T0 = 1_700_000_000_000L
    }
}
