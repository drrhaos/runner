package com.runner.academy.ui.workout

import com.runner.academy.data.WorkoutMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HistoryIndexTest {

    private val october = WorkoutMonth(2026, 10, 3)
    private val september = WorkoutMonth(2026, 9, 1)
    private val december = WorkoutMonth(2025, 12, 2)
    private val index = HistoryIndex(listOf(october, september, december))

    @Test
    fun `the total is the length of the list`() {
        assertEquals(6, index.total)
    }

    @Test
    fun `each position falls in its month in list order`() {
        assertEquals(
            listOf(october, october, october, september, december, december),
            (0 until 6).map { index.monthAt(it) }
        )
    }

    @Test
    fun `positions outside the list are clamped`() {
        assertEquals(october, index.monthAt(-5))
        assertEquals(december, index.monthAt(100))
    }

    @Test
    fun `an empty list has no month`() {
        assertNull(HistoryIndex.EMPTY.monthAt(0))
        assertEquals(0, HistoryIndex.EMPTY.total)
    }
}
