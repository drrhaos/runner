package com.runner.academy.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class StrideModelTest {

    @Test
    fun `height prior gives plausible running stride`() {
        val model = StrideModel.fromHeight(175f)
        // ~0.65 x height at an easy-run cadence of 165 spm
        assertEquals(1.14f, model.strideMeters(165f), 0.03f)
        // walking cadence gives roughly 0.415 x height
        assertEquals(0.73f, model.strideMeters(110f), 0.03f)
    }

    @Test
    fun `stride grows with cadence`() {
        val model = StrideModel.fromHeight(175f)
        assertTrue(model.strideMeters(180f) > model.strideMeters(160f))
    }

    @Test
    fun `taller runner has longer prior stride`() {
        assertTrue(
            StrideModel.fromHeight(190f).strideMeters(165f) >
                StrideModel.fromHeight(160f).strideMeters(165f)
        )
    }

    @Test
    fun `implausible height falls back to default`() {
        assertEquals(
            StrideModel.fromHeight(175f).strideMeters(165f),
            StrideModel.fromHeight(0f).strideMeters(165f),
            1e-4f
        )
        assertEquals(
            StrideModel.fromHeight(175f).strideMeters(165f),
            StrideModel.fromHeight(Float.NaN).strideMeters(165f),
            1e-4f
        )
    }

    @Test
    fun `stride is clamped for extreme cadence`() {
        val model = StrideModel.fromHeight(175f)
        val low = model.strideMeters(0f)
        val high = model.strideMeters(400f)
        assertTrue(low >= StrideModel.MIN_STRIDE_M)
        assertTrue(high <= StrideModel.MAX_STRIDE_M)
        assertEquals(model.strideMeters(StrideModel.MAX_CADENCE), high, 1e-4f)
        assertTrue(model.strideMeters(Float.NaN).isFinite())
    }

    @Test
    fun `distance uses stride times steps and default cadence when unknown`() {
        val model = StrideModel.fromHeight(175f)
        assertEquals(model.strideMeters(170f) * 100, model.distanceMeters(100, 170f), 1e-3f)
        assertEquals(
            model.strideMeters(StrideModel.DEFAULT_CADENCE) * 100,
            model.distanceMeters(100, null),
            1e-3f
        )
        assertEquals(0f, model.distanceMeters(0, 170f), 0f)
        assertEquals(0f, model.distanceMeters(-5, 170f), 0f)
    }

    @Test
    fun `learning converges towards the observed stride`() {
        val model = StrideModel.fromHeight(175f)
        // True runner: 1.30 m at 170 spm (prior says ~1.18 m)
        repeat(40) { assertTrue(model.learn(260f, 200, 170f)) }
        assertEquals(1.30f, model.strideMeters(170f), 0.02f)
        assertEquals(40, model.sampleCount)
    }

    @Test
    fun `learning picks up the slope from samples at different cadences`() {
        val model = StrideModel.fromHeight(175f)
        // True runner: stride = 0.20 + 0.0060 * cadence
        fun truth(c: Float) = 0.20f + 0.0060f * c
        repeat(60) { i ->
            val c = if (i % 2 == 0) 150f else 180f
            model.learn(truth(c) * 300, 300, c)
        }
        assertEquals(truth(150f), model.strideMeters(150f), 0.03f)
        assertEquals(truth(180f), model.strideMeters(180f), 0.03f)
    }

    @Test
    fun `single update is bounded`() {
        val model = StrideModel.fromHeight(175f)
        val before = model.strideMeters(165f)
        // 25 % longer than predicted: accepted, but moves at most MAX_UPDATE_FRACTION
        model.learn(before * 1.25f * 300, 300, 165f)
        val after = model.strideMeters(165f)
        assertTrue(after > before)
        assertTrue(after <= before * (1 + StrideModel.MAX_UPDATE_FRACTION) + 1e-4f)
    }

    @Test
    fun `outliers are rejected`() {
        val model = StrideModel.fromHeight(175f)
        val before = model.strideMeters(165f)
        assertFalse(model.learn(before * 2f * 300, 300, 165f)) // GPS jump
        assertFalse(model.learn(before * 0.4f * 300, 300, 165f)) // GPS stall
        assertEquals(before, model.strideMeters(165f), 1e-6f)
        assertEquals(0, model.sampleCount)
    }

    @Test
    fun `tiny or invalid samples are ignored`() {
        val model = StrideModel.fromHeight(175f)
        val before = model.strideMeters(165f)
        assertFalse(model.learn(12f, 10, 165f)) // too few steps
        assertFalse(model.learn(10f, 200, 165f)) // too short
        assertFalse(model.learn(230f, 200, 20f)) // cadence implausible
        assertFalse(model.learn(Float.NaN, 200, 165f))
        assertFalse(model.learn(230f, 200, Float.NaN))
        assertEquals(before, model.strideMeters(165f), 1e-6f)
    }

    @Test
    fun `one bad sample among many does not drag the model`() {
        val model = StrideModel.fromHeight(175f)
        repeat(30) { model.learn(1.25f * 200, 200, 170f) }
        val settled = model.strideMeters(170f)
        model.learn(1.55f * 200, 200, 170f) // +24 %: inside the gate
        assertTrue(abs(model.strideMeters(170f) - settled) < 0.03f)
    }

    @Test
    fun `serialisation round-trips the learned state`() {
        val model = StrideModel.fromHeight(175f)
        repeat(10) { model.learn(1.30f * 200, 200, 170f) }
        val restored = StrideModel.deserialize(model.serialize(), 175f)
        assertEquals(model.strideMeters(150f), restored.strideMeters(150f), 1e-5f)
        assertEquals(model.strideMeters(185f), restored.strideMeters(185f), 1e-5f)
        assertEquals(model.sampleCount, restored.sampleCount)
        // keeps learning the same way
        model.learn(1.28f * 200, 200, 175f)
        restored.learn(1.28f * 200, 200, 175f)
        assertEquals(model.strideMeters(175f), restored.strideMeters(175f), 1e-5f)
    }

    @Test
    fun `corrupt or missing state falls back to the height prior`() {
        val prior = StrideModel.fromHeight(182f).strideMeters(165f)
        for (bad in listOf(null, "", "garbage", "v1;1;2", "v9;0.5;0.004;0.01;0;0.00001;3", "v1;NaN;0.004;0.01;0;0.00001;3")) {
            assertEquals("for '$bad'", prior, StrideModel.deserialize(bad, 182f).strideMeters(165f), 1e-5f)
        }
    }

    @Test
    fun `untrained state follows the current height`() {
        val stored = StrideModel.fromHeight(175f).serialize()
        assertEquals(
            StrideModel.fromHeight(190f).strideMeters(165f),
            StrideModel.deserialize(stored, 190f).strideMeters(165f),
            1e-5f
        )
    }

    @Test
    fun `parameters expose the linear form`() {
        val model = StrideModel.fromHeight(175f)
        assertEquals(
            model.intercept + model.slope * 160f,
            model.strideMeters(160f),
            1e-4f
        )
        assertNull(StrideModel.deserialize("v1;x", 175f).takeIf { it.sampleCount > 0 })
    }
}
