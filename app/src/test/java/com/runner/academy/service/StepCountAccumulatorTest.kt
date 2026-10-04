package com.runner.academy.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StepCountAccumulatorTest {

    private val sec = 1_000_000_000L

    private fun started(at: Long = 0L, initial: Int = 0) =
        StepCountAccumulator().apply { start(at, initial) }

    @Test
    fun `first counter value is only a baseline`() {
        val acc = started()
        acc.onCounterValue(10_000L, 1 * sec)
        assertEquals(0, acc.steps)
    }

    @Test
    fun `counter deltas accumulate after baseline`() {
        val acc = started()
        acc.onCounterValue(10_000L, 1 * sec)
        acc.onCounterValue(10_005L, 2 * sec)
        acc.onCounterValue(10_012L, 3 * sec)
        assertEquals(12, acc.steps)
    }

    @Test
    fun `initial steps continue a restored workout`() {
        val acc = started(initial = 500)
        acc.onCounterValue(42L, 1 * sec)
        acc.onCounterValue(52L, 2 * sec)
        assertEquals(510, acc.steps)
    }

    @Test
    fun `counter reset after reboot counts the new value as steps`() {
        val acc = started()
        acc.onCounterValue(10_000L, 1 * sec)
        acc.onCounterValue(10_010L, 2 * sec)
        acc.onCounterValue(3L, 3 * sec)
        assertEquals(13, acc.steps)
    }

    @Test
    fun `steps during pause are not counted`() {
        val acc = started()
        acc.onCounterValue(100L, 1 * sec)
        acc.onCounterValue(120L, 2 * sec)
        acc.pause()
        acc.onCounterValue(150L, 3 * sec)
        acc.onCounterValue(180L, 4 * sec)
        acc.resume(5 * sec)
        acc.onCounterValue(190L, 6 * sec)
        assertEquals(30, acc.steps)
    }

    @Test
    fun `pause before any counter value still drops paused steps`() {
        val acc = started()
        acc.pause()
        acc.onCounterValue(100L, 1 * sec)
        acc.onCounterValue(140L, 2 * sec)
        acc.resume(3 * sec)
        acc.onCounterValue(150L, 4 * sec)
        assertEquals(10, acc.steps)
    }

    @Test
    fun `detector steps count one by one and respect pause`() {
        val acc = started()
        repeat(5) { acc.onDetectedSteps(1, (it + 1) * sec) }
        acc.pause()
        acc.onDetectedSteps(3, 7 * sec)
        acc.resume(8 * sec)
        acc.onDetectedSteps(2, 9 * sec)
        assertEquals(7, acc.steps)
    }

    @Test
    fun `nothing is counted before start`() {
        val acc = StepCountAccumulator()
        acc.onDetectedSteps(4, 1 * sec)
        acc.onCounterValue(5L, 1 * sec)
        acc.onCounterValue(15L, 2 * sec)
        assertEquals(0, acc.steps)
        assertNull(acc.cadence(3 * sec))
    }

    @Test
    fun `cadence is null until the window is long enough`() {
        val acc = started()
        acc.onCounterValue(0L, 0L)
        acc.onCounterValue(9L, 3 * sec)
        assertNull(acc.cadence(3 * sec))
    }

    @Test
    fun `steady 3 steps per second gives 180 spm`() {
        val acc = started()
        acc.onCounterValue(0L, 0L)
        for (s in 1..30) acc.onCounterValue(3L * s, s * sec)
        val cadence = acc.cadence(30 * sec)
        assertNotNull(cadence)
        assertEquals(180f, cadence!!, 1f)
    }

    @Test
    fun `cadence follows the recent window not the whole run`() {
        val acc = started()
        acc.onCounterValue(0L, 0L)
        var total = 0L
        for (s in 1..60) {
            total += if (s <= 30) 2 else 3 // 120 spm then 180 spm
            acc.onCounterValue(total, s * sec)
        }
        assertEquals(180f, acc.cadence(60 * sec)!!, 1f)
    }

    @Test
    fun `cadence drops to zero when standing still`() {
        val acc = started()
        acc.onCounterValue(0L, 0L)
        for (s in 1..20) acc.onCounterValue(3L * s, s * sec)
        assertEquals(0f, acc.cadence(60 * sec)!!, 0.01f)
    }

    @Test
    fun `cadence is null while paused and restarts its window on resume`() {
        val acc = started()
        acc.onCounterValue(0L, 0L)
        for (s in 1..20) acc.onCounterValue(3L * s, s * sec)
        acc.pause()
        assertNull(acc.cadence(21 * sec))
        acc.resume(100 * sec)
        assertNull(acc.cadence(102 * sec))
        acc.onCounterValue(63L, 101 * sec)
        for (s in 1..10) acc.onCounterValue(63L + 2L * s, (101 + s) * sec)
        val cadence = acc.cadence(111 * sec)!!
        assertTrue("cadence $cadence should reflect only post-resume steps", cadence in 110f..135f)
    }

    @Test
    fun `isPaused reflects state`() {
        val acc = started()
        assertFalse(acc.isPaused)
        acc.pause()
        assertTrue(acc.isPaused)
        acc.resume(1 * sec)
        assertFalse(acc.isPaused)
    }

    @Test
    fun `start resets previous counts`() {
        val acc = started()
        acc.onCounterValue(0L, 0L)
        acc.onCounterValue(50L, 1 * sec)
        acc.start(2 * sec, 0)
        acc.onCounterValue(60L, 3 * sec)
        assertEquals(0, acc.steps)
    }

    @Test
    fun `steps at an earlier time come from the history`() {
        val acc = started()
        acc.onDetectedSteps(3, 1 * sec)
        acc.onDetectedSteps(3, 2 * sec)
        acc.onDetectedSteps(3, 3 * sec)

        assertEquals(0, acc.stepsAt(500_000_000L))
        assertEquals(3, acc.stepsAt(1 * sec))
        assertEquals(6, acc.stepsAt(2 * sec + 500_000_000L))
        assertEquals(9, acc.stepsAt(10 * sec))
    }

    @Test
    fun `steps at a time before the kept history are the oldest known`() {
        val acc = started(at = 0L, initial = 100)
        for (i in 1..400) acc.onDetectedSteps(1, i * sec)
        assertEquals(400 + 100, acc.steps)
        assertTrue(acc.stepsAt(0L) >= 100)
        assertTrue(acc.stepsAt(0L) <= acc.stepsAt(399 * sec))
    }
}
