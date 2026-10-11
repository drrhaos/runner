package com.runner.academy.sensor

import org.junit.Assert.assertEquals
import org.junit.Test

class SensorScanTest {

    @Test
    fun `five starts in 30 s are allowed`() {
        assertEquals(0L, ScanStartLimit.waitMs(emptyList(), nowMs = 0))
        assertEquals(0L, ScanStartLimit.waitMs(listOf(0, 1_000, 2_000, 3_000), nowMs = 4_000))
    }

    @Test
    fun `the sixth start waits until the oldest of five is 30 s old`() {
        val starts = listOf(10_000L, 11_000, 12_000, 13_000, 14_000)
        assertEquals(25_000L, ScanStartLimit.waitMs(starts, nowMs = 15_000))
        assertEquals(1L, ScanStartLimit.waitMs(starts, nowMs = 39_999))
        assertEquals(0L, ScanStartLimit.waitMs(starts, nowMs = 40_000))
    }

    @Test
    fun `only the last five starts count`() {
        val starts = listOf(0L, 5_000, 20_000, 21_000, 22_000, 23_000)
        // The five newest start at 5 s: free at 35 s
        assertEquals(10_000L, ScanStartLimit.waitMs(starts, nowMs = 25_000))
    }

    @Test
    fun `recorded starts keep only what can still matter`() {
        val kept = ScanStartLimit.record(listOf(0L, 1_000, 2_000, 3_000, 4_000), nowMs = 5_000)
        assertEquals(listOf(1_000L, 2_000, 3_000, 4_000, 5_000), kept)
    }

    @Test
    fun `found devices keep their order, the newest signal and a known name`() {
        val strap = FoundSensor("AA", "HR Strap 10", -60)
        val chestStrap = FoundSensor("BB", "Chest Strap", -80)
        var list = emptyList<FoundSensor>().withFound(strap)
        list = list.withFound(chestStrap)
        list = list.withFound(FoundSensor("AA", null, -50))
        assertEquals(listOf(FoundSensor("AA", "HR Strap 10", -50), chestStrap), list)
    }
}
