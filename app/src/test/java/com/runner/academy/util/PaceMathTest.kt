package com.runner.academy.util

import org.junit.Assert.assertEquals
import org.junit.Test

class PaceMathTest {

    @Test
    fun avgPace_isMovingMinutesPerKm() {
        assertEquals(5f, PaceMath.avgPace(10f, 50 * 60_000L), 0.0001f)
        assertEquals(6.5f, PaceMath.avgPace(2f, 13 * 60_000L), 0.0001f)
    }

    @Test
    fun avgPace_zeroDistanceOrTime_isZeroNotNaN() {
        assertEquals(0f, PaceMath.avgPace(0f, 60_000L), 0f)
        assertEquals(0f, PaceMath.avgPace(5f, 0L), 0f)
        assertEquals(0f, PaceMath.avgPace(0f, 0L), 0f)
        assertEquals(0f, PaceMath.avgPace(-1f, 60_000L), 0f)
        assertEquals(0f, PaceMath.avgPace(Float.NaN, 60_000L), 0f)
    }
}
