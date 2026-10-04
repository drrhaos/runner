package com.runner.academy.util

import android.location.Location
import com.runner.academy.data.WorkoutType
import com.runner.academy.util.TrackFilter.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.cos

/**
 * Steps in [TrackFilter]: bridges take `max(straight line, steps × stride)`, the distance of an
 * open false-signal episode is counted live from steps ([TrackFilter.pendingMeters]) and a false
 * start counts its steps as a lead-in on the first good fix.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TrackFilterStepsTest {

    /** One metre per step, so step counts read as metres. */
    private val filter = TrackFilter(WorkoutType.EASY_RUN, stepDistance = { steps, _ -> steps * 1f })

    private fun fix(eastM: Double, northM: Double, sec: Int, speed: Float = 3f): Location =
        Location("test").apply {
            latitude = LAT0 + northM / M_PER_DEG
            longitude = LON0 + eastM / (M_PER_DEG * cos(Math.toRadians(LAT0)))
            time = T0 + sec * 1_000L
            accuracy = 6f
            this.speed = speed
            altitude = 150.0
        }

    /** Runs east at 3 m/s and 3 steps/s from [fromSec] to [toSec]. */
    private fun runEast(fromSec: Int, toSec: Int) {
        for (sec in fromSec..toSec) {
            val verdict = filter.process(fix(sec * 3.0, 0.0, sec), steps = sec * 3)
            assertTrue("fix at $sec s", verdict is Verdict.Accepted)
        }
    }

    /** Fixes frozen at the position of second 10 (30 m east) from [fromSec] to [toSec], steps going on. */
    private fun frozen(fromSec: Int, toSec: Int, stepsAt: (Int) -> Int = { it * 3 }) {
        val at = fix(30.0, 0.0, 0)
        for (sec in fromSec..toSec) {
            filter.process(Location(at).apply { time = T0 + sec * 1_000L }, steps = stepsAt(sec))
        }
    }

    private fun Verdict.accepted() = this as Verdict.Accepted

    @Test
    fun `steps longer than the straight line win and mark the bridge as pedometer`() {
        runEast(0, 10)
        frozen(11, 60)

        // Ran a U: 153 m of steps since the anchor, but back only 60 m east of it
        val back = filter.process(fix(90.0, 0.0, 61), steps = 183).accepted()

        assertTrue(back.afterGap)
        assertEquals(153f, back.bridgeMeters!!, 0.5f)
        assertTrue(back.bridgeFromSteps)
    }

    @Test
    fun `a straight line longer than the steps wins`() {
        runEast(0, 10)
        frozen(11, 60, stepsAt = { 30 + (it - 10) })

        val back = filter.process(fix(183.0, 0.0, 61), steps = 81).accepted()

        assertEquals(153f, back.bridgeMeters!!, 0.5f)
        assertFalse(back.bridgeFromSteps)
    }

    @Test
    fun `without an estimator steps are ignored`() {
        val plain = TrackFilter(WorkoutType.EASY_RUN)
        for (sec in 0..10) plain.process(fix(sec * 3.0, 0.0, sec), steps = sec * 3)
        val at = fix(30.0, 0.0, 0)
        for (sec in 11..60) plain.process(Location(at).apply { time = T0 + sec * 1_000L }, steps = sec * 3)

        val back = plain.process(fix(90.0, 0.0, 61), steps = 183).accepted()

        assertEquals(60f, back.bridgeMeters!!, 0.5f)
        assertFalse(back.bridgeFromSteps)
        assertEquals(0f, plain.pendingMeters, 0f)
    }

    @Test
    fun `a long episode is counted live and the bridge adds only the remainder`() {
        runEast(0, 10)
        val before = filter.countedMeters
        assertEquals(30f, before, 0.5f)

        var shown = before
        frozen(11, 60)
        for (sec in 61..90) {
            filter.process(fix(5_000.0, 0.0, sec), steps = sec * 3)
            assertTrue("counted never goes down", filter.countedMeters >= shown)
            shown = filter.countedMeters
        }
        // Counted from steps since the anchor (30 steps at 10 s)
        assertEquals(270f - 30f, filter.pendingMeters, 0.5f)

        val back = filter.process(fix(90.0, 0.0, 91), steps = 273).accepted()

        assertEquals(243f, back.bridgeMeters!!, 0.5f)
        assertEquals(0f, filter.pendingMeters, 0f)
        assertEquals(before + 243f, filter.countedMeters, 0.5f)
        assertEquals(filter.committedMeters, filter.countedMeters, 0f)
    }

    @Test
    fun `a short episode is not counted live and closes as an ordinary step`() {
        runEast(0, 10)
        for (sec in 11..15) filter.process(fix(5_000.0, 0.0, sec), steps = sec * 3)

        assertTrue(filter.inFalseSignal)
        assertEquals(0f, filter.pendingMeters, 0f)
        val back = filter.process(fix(48.0, 0.0, 16), steps = 48).accepted()
        assertFalse(back.afterGap)
        assertEquals(18f, back.segmentDistanceMeters, 0.5f)
    }

    @Test
    fun `a false start counts its steps as a lead-in on the first good fix`() {
        for (sec in 0..40) {
            filter.process(fix(15_000.0, 8_000.0, sec, speed = 0f), steps = sec * 3)
        }
        assertTrue(filter.inFalseSignal)
        assertNull(filter.anchor)
        assertEquals(120f, filter.pendingMeters, 0.5f)

        val first = filter.process(fix(0.0, 0.0, 41), steps = 123).accepted()

        assertFalse(first.afterGap)
        assertEquals(0f, first.segmentDistanceMeters, 0f)
        assertEquals(123f, first.leadInMeters!!, 0.5f)
        assertEquals(123f, filter.countedMeters, 0.5f)
        assertEquals(0f, filter.pendingMeters, 0f)
    }

    @Test
    fun `steps run while GPS warms up count as a lead-in`() {
        // Owner's decision: a silence is counted by steps, the one at the start too
        val first = filter.process(fix(0.0, 0.0, 20), steps = 60).accepted()

        assertEquals(60f, first.leadInMeters!!, 0.5f)
        assertEquals(60f, filter.countedMeters, 0.5f)
    }

    @Test
    fun `a retracted start keeps its counted lead-in`() {
        for (sec in 0..30) filter.process(fix(15_000.0, 8_000.0, sec, speed = 0f), steps = sec * 3)
        val start = fix(0.0, 0.0, 31, speed = 0f)
        assertTrue(filter.process(start, steps = 93) is Verdict.Accepted)
        val shown = filter.countedMeters
        filter.process(Location(start).apply { time += 1_000 }, steps = 96)
        val third = filter.process(Location(start).apply { time += 2_000 }, steps = 99) as Verdict.Rejected

        assertTrue(third.retractStart)
        assertTrue(filter.countedMeters >= shown)
    }

    @Test
    fun `steps during a silence before a far burst are counted and the burst is dropped`() {
        runEast(0, 10)
        // Two minutes without fixes, then a far burst and a plausible good fix
        filter.process(fix(9_000.0, 9_500.0, 130), steps = 390)
        filter.process(fix(9_000.0, 9_500.0, 131), steps = 393)
        // Owner's decision: a silence is counted by steps (393 - 30 at the anchor)
        assertEquals(363f, filter.pendingMeters, 0.5f)

        val back = filter.process(fix(400.0, 0.0, 132), steps = 396).accepted()

        assertTrue(back.afterGap)
        // max(straight line 370 m, steps 366 m, already shown 363 m)
        assertEquals(370f, back.bridgeMeters!!, 1f)
        assertEquals(400f, filter.countedMeters, 1f)
    }

    @Test
    fun `steps counted before a silence are kept as a pedometer bridge`() {
        runEast(0, 10)
        frozen(11, 40)
        val shown = filter.countedMeters
        assertTrue(filter.pendingMeters > 0f)

        // Fixes stop for a minute, then a good fix
        val back = filter.process(fix(400.0, 0.0, 100), steps = 300).accepted()

        assertTrue(back.afterGap)
        // max(straight line 370 m, steps 270 m, already shown): never less than what was shown
        val expected = maxOf(370f, 270f, shown - 30f)
        assertEquals(expected, back.bridgeMeters!!, 1f)
        assertTrue(filter.countedMeters >= shown)
    }

    @Test
    fun `reset carries the counted pending distance of a restored episode`() {
        val anchor = fix(30.0, 0.0, 10)
        filter.reset(anchor, anchorSteps = 30, pendingMeters = 100f)

        assertEquals(100f, filter.countedMeters, 0f)
        val back = filter.process(fix(60.0, 0.0, 60), steps = 100).accepted()
        assertTrue(back.afterGap)
        assertEquals(100f, back.bridgeMeters!!, 0.5f)
        assertEquals(100f, filter.countedMeters, 0.5f)
    }

    private companion object {
        const val LAT0 = 55.75
        const val LON0 = 37.6
        const val M_PER_DEG = 111_320.0
        const val T0 = 1_700_000_000_000L
    }
}
