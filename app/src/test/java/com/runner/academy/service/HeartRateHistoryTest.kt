package com.runner.academy.service

import com.runner.academy.sensor.HeartRateSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HeartRateHistoryTest {

    private val s = 1_000_000_000L
    private val t = 1_000 * s

    private fun history(vararg samples: Pair<Long, Int?>) = HeartRateHistory().apply {
        samples.forEach { (at, bpm) -> add(HeartRateSample(at, bpm)) }
    }

    @Test
    fun `a fix takes the newest sample within five seconds before it`() {
        val history = history(t - 7 * s to 130, t - 2 * s to 150)

        assertEquals(150, history.bpmAt(t))
    }

    @Test
    fun `a sample older than five seconds is no value`() {
        val history = history(t - 7 * s to 130)

        assertNull(history.bpmAt(t))
    }

    @Test
    fun `no contact after a valid sample is no value`() {
        val history = history(t - 3 * s to 150, t - 1 * s to null)

        assertNull(history.bpmAt(t))
    }

    @Test
    fun `without a sample before the fix one up to a second after it counts`() {
        assertEquals(152, history(t + s / 2 to 152).bpmAt(t))
        assertNull(history(t + 2 * s to 152).bpmAt(t))
    }

    @Test
    fun `a sample before the fix wins over a closer one after it`() {
        val history = history(t - 4 * s to 140, t + s / 10 to 160)

        assertEquals(140, history.bpmAt(t))
    }

    @Test
    fun `samples older than two minutes are dropped`() {
        val history = history(t to 140, t + 121 * s to 150)

        assertEquals(1, history.size)
        assertNull(history.bpmAt(t))
    }

    @Test
    fun `the latest sample and clear`() {
        val history = history(t to 140, t + s to null)

        assertEquals(HeartRateSample(t + s, null), history.latest())
        history.clear()
        assertNull(history.latest())
        assertEquals(0, history.size)
    }

    @Test
    fun `a late sample does not move the latest back`() {
        val history = history(t + s to 150, t to 140)

        assertEquals(HeartRateSample(t + s, 150), history.latest())
        assertEquals(140, history.bpmAt(t))
    }
}
