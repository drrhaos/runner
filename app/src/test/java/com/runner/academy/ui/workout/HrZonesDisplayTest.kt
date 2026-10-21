package com.runner.academy.ui.workout

import com.runner.academy.util.HrMax
import com.runner.academy.util.HrMaxSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HrZonesDisplayTest {

    private val hrMax = HrMax(190, HrMaxSource.AUTO)

    private fun value(vararg ms: Long) = HrZonesDisplay.of(hrMax, ms) as HrZonesDisplay.Value

    @Test
    fun `no zones or no time in them is no card`() {
        assertEquals(HrZonesDisplay.None, HrZonesDisplay.of(null, longArrayOf(0, 0, 0, 60_000, 0, 0)))
        assertEquals(HrZonesDisplay.None, HrZonesDisplay.of(hrMax, null))
    }

    @Test
    fun `rows go from Z5 down to below Z1, all six even when empty`() {
        val rows = value(0, 0, 0, 60_000, 0, 0).rows

        assertEquals(listOf(5, 4, 3, 2, 1, 0), rows.map { it.zone })
        assertEquals(listOf(0L, 0L, 60_000L, 0L, 0L, 0L), rows.map { it.ms })
        assertEquals(listOf(0, 0, 100, 0, 0, 0), rows.map { it.percent })
    }

    @Test
    fun `a row carries its beats, below Z1 up to the Z1 bound`() {
        val rows = value(0, 0, 0, 60_000, 0, 0).rows.associateBy { it.zone }

        assertEquals(171..190, rows.getValue(5).range)
        assertEquals(152..170, rows.getValue(4).range)
        assertEquals(95..113, rows.getValue(1).range)
        assertEquals(95, rows.getValue(0).belowBpm)
    }

    @Test
    fun `percents are of the covered time and sum to exactly 100`() {
        // Three equal thirds: 33.3 each, the remainder goes to the first of the ties
        val rows = value(0, 20_000, 20_000, 20_000, 0, 0).rows.associateBy { it.zone }

        assertEquals(34, rows.getValue(1).percent)
        assertEquals(33, rows.getValue(2).percent)
        assertEquals(33, rows.getValue(3).percent)
        assertEquals(100, rows.values.sumOf { it.percent })
    }

    @Test
    fun `the largest remainders get the rounding`() {
        // 2/7, 2/7, 3/7 = 28.57, 28.57, 42.86: floors 28 + 28 + 42 = 98; .86 gets one, then the first .57
        assertEquals(listOf(29, 28, 43), HrZonesDisplay.largestRemainderPercents(longArrayOf(2, 2, 3)).toList())
        // 0.4 % rounds away to the larger remainders, 99.6 % does not round past 100
        assertEquals(listOf(100, 0), HrZonesDisplay.largestRemainderPercents(longArrayOf(996, 4)).toList())
        assertEquals(listOf(0, 0), HrZonesDisplay.largestRemainderPercents(longArrayOf(0, 0)).toList())
    }

    @Test
    fun `a bar is the share of the covered time`() {
        val rows = value(15_000, 0, 0, 45_000, 0, 0).rows.associateBy { it.zone }

        assertEquals(0.25f, rows.getValue(0).fraction, 0.0001f)
        assertEquals(0.75f, rows.getValue(3).fraction, 0.0001f)
        assertTrue(rows.getValue(5).fraction == 0f)
    }

    @Test
    fun `the basis is the HRmax and its source`() {
        val display = HrZonesDisplay.of(HrMax(185, HrMaxSource.MANUAL), longArrayOf(0, 0, 0, 60_000, 0, 0)) as HrZonesDisplay.Value

        assertEquals(HrMax(185, HrMaxSource.MANUAL), display.hrMax)
        assertEquals(93, display.rows.last().belowBpm)
    }
}
