package com.runner.academy.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MovingTimeDisplayTest {

    @Test
    fun `details show the elapsed time only when it differs by 5 s or more`() {
        assertFalse(MovingTimeDisplay.detailShowsElapsed(1_800_000L, 1_800_000L))
        assertFalse(MovingTimeDisplay.detailShowsElapsed(1_800_000L, 1_795_001L))
        assertTrue(MovingTimeDisplay.detailShowsElapsed(1_800_000L, 1_795_000L))
        // Moving above elapsed (should not happen) shows one time
        assertFalse(MovingTimeDisplay.detailShowsElapsed(1_000L, 9_000L))
    }

    @Test
    fun `statistics show the moving time only when the sums differ by more than a minute`() {
        assertFalse(MovingTimeDisplay.statisticsShowsMoving(36_000_000L, 36_000_000L))
        assertFalse(MovingTimeDisplay.statisticsShowsMoving(36_000_000L, 35_940_000L))
        assertTrue(MovingTimeDisplay.statisticsShowsMoving(36_000_000L, 35_939_999L))
    }
}
