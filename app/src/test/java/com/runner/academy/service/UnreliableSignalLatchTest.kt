package com.runner.academy.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnreliableSignalLatchTest {

    private val latch = UnreliableSignalLatch(minGoodFixes = 3, minGoodMs = 5_000L)

    @Test
    fun `enters at the first false fix`() {
        assertFalse(latch.onFix(inFalseSignal = false, good = true, timeMs = 0))
        assertTrue(latch.onFix(inFalseSignal = true, good = false, timeMs = 1_000))
    }

    @Test
    fun `leaves only after enough good fixes and time`() {
        latch.onFix(inFalseSignal = true, good = false, timeMs = 0)
        assertTrue(latch.onFix(false, good = true, timeMs = 1_000))
        assertTrue(latch.onFix(false, good = true, timeMs = 2_000))
        assertTrue("three fixes, but only 3 s", latch.onFix(false, good = true, timeMs = 3_000))
        assertTrue(latch.onFix(false, good = true, timeMs = 4_000))
        assertFalse(latch.onFix(false, good = true, timeMs = 5_000))
    }

    @Test
    fun `a false fix in between starts the count again`() {
        latch.onFix(true, good = false, timeMs = 0)
        repeat(4) { latch.onFix(false, good = true, timeMs = 1_000L + it * 1_000) }
        latch.onFix(true, good = false, timeMs = 5_000)
        repeat(4) { assertTrue(latch.onFix(false, good = true, timeMs = 6_000L + it * 1_000)) }
        assertFalse(latch.onFix(false, good = true, timeMs = 10_000))
    }

    @Test
    fun `dropped fixes that are not a false signal do not count as good`() {
        latch.onFix(true, good = false, timeMs = 0)
        repeat(10) { assertTrue(latch.onFix(false, good = false, timeMs = 1_000L + it * 1_000)) }
    }

    @Test
    fun `reset clears the latch`() {
        latch.onFix(true, good = false, timeMs = 0)
        latch.reset()
        assertFalse(latch.isUnreliable)
    }
}
