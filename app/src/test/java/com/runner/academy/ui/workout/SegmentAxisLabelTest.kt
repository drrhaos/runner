package com.runner.academy.ui.workout

import org.junit.Assert.assertEquals
import org.junit.Test

class SegmentAxisLabelTest {

    @Test
    fun `a title that fits stays whole`() {
        assertEquals("Разминка", ChartRenderer.shortAxisLabel("Разминка"))
    }

    @Test
    fun `a long title is cut with an ellipsis, not mid-word silently`() {
        assertEquals("Восстан…", ChartRenderer.shortAxisLabel("Восстановление"))
    }

    @Test
    fun `no space is left before the ellipsis`() {
        assertEquals("Run 12…", ChartRenderer.shortAxisLabel("Run 12 hard"))
    }

    @Test
    fun `a blank title stays blank for the numbered fallback`() {
        assertEquals("", ChartRenderer.shortAxisLabel("   "))
    }
}
