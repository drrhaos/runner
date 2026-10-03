package com.runner.academy.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StrideLearnerTest {

    private val model = StrideModel.fromHeight(175f)
    private val learner = StrideLearner(model, minDurationMs = 30_000L, minDistanceM = 100f)

    /** Feeds [seconds] of 1 Hz fixes at 3.3 m/s and 3 steps/s; returns how many samples were learned. */
    private fun run(fromSec: Int, seconds: Int, stride: Double = 1.1, reliable: Boolean = true): Int {
        var learned = 0
        for (s in fromSec until fromSec + seconds) {
            val used = learner.onAccepted(
                segmentMeters = (3.0 * stride).toFloat(),
                afterGap = false,
                bridged = false,
                steps = s * 3,
                timeMs = s * 1_000L,
                reliable = reliable
            )
            if (used) learned++
        }
        return learned
    }

    @Test
    fun `a long good stretch teaches the model`() {
        val before = model.strideMeters(180f)
        assertTrue(run(0, 120) > 0)
        assertTrue(model.sampleCount > 0)
        assertTrue("moves toward the observed 1.1 m", model.strideMeters(180f) < before)
    }

    @Test
    fun `short stretches teach nothing`() {
        assertEquals(0, run(0, 20))
        assertEquals(0, model.sampleCount)
    }

    @Test
    fun `gaps, bridges and unreliable fixes restart the stretch`() {
        run(0, 25)
        learner.onAccepted(500f, afterGap = true, bridged = true, steps = 25 * 3, timeMs = 25_000L, reliable = true)
        assertEquals(0, run(26, 25))
        assertEquals(0, run(51, 60, reliable = false))
        assertEquals(0, model.sampleCount)
    }

    @Test
    fun `fixes without steps teach nothing`() {
        for (s in 0 until 120) {
            assertFalse(learner.onAccepted(3.3f, false, false, steps = null, timeMs = s * 1_000L, reliable = true))
        }
    }
}
