package com.runner.academy.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PressureAltitudeHistoryTest {

    private val sec = 1_000_000_000L

    /** Altitude in metres straight through, so the medians are easy to read. */
    private fun history() = PressureAltitudeHistory(toMeters = { it })

    @Test
    fun `standard atmosphere altitude of sea level pressure is zero, 1 hPa is about 8 m`() {
        assertEquals(0f, PressureAltitudeHistory.standardAltitudeM(1013.25f), 0.01f)
        assertEquals(8.4f, PressureAltitudeHistory.standardAltitudeM(1012.25f), 0.2f)
        // Higher means lower pressure: 900 hPa is about 990 m
        assertEquals(988f, PressureAltitudeHistory.standardAltitudeM(900f), 5f)
    }

    @Test
    fun `empty history has no altitude`() {
        assertNull(history().altitudeAt(5 * sec))
    }

    @Test
    fun `altitude at a moment is the median of the readings within 2 s of it`() {
        val h = history()
        // A spike at 10 s (a gust on the vent) is outvoted by its neighbours
        listOf(100f, 101f, 102f, 160f, 104f, 105f, 106f).forEachIndexed { i, m -> h.add((7 + i) * sec, m) }

        assertEquals(104f, h.altitudeAt(10 * sec)!!, 0.001f)
        // At the edge only the readings within the window count: 11..13 s → 104, 105, 106
        assertEquals(105f, h.altitudeAt(13 * sec)!!, 0.001f)
    }

    @Test
    fun `even count takes the mean of the middle two`() {
        val h = history()
        h.add(10 * sec, 100f)
        h.add(11 * sec, 102f)
        assertEquals(101f, h.altitudeAt(10 * sec + sec / 2)!!, 0.001f)
    }

    @Test
    fun `a moment with no reading within 2 s has none`() {
        val h = history()
        h.add(10 * sec, 100f)
        assertNull(h.altitudeAt(13 * sec))
        assertNull(h.altitudeAt(7 * sec))
    }

    @Test
    fun `readings older than the history are dropped`() {
        val h = history()
        h.add(0L, 50f)
        h.add(200 * sec, 100f)
        assertNull(h.altitudeAt(1 * sec))
        assertEquals(1, h.size)
    }

    @Test
    fun `an invalid pressure is ignored, clear forgets everything`() {
        val h = PressureAltitudeHistory()
        h.add(1 * sec, 0f)
        h.add(1 * sec, Float.NaN)
        h.add(1 * sec, -3f)
        assertEquals(0, h.size)
        h.add(1 * sec, 1000f)
        assertEquals(1, h.size)
        h.clear()
        assertNull(h.altitudeAt(1 * sec))
    }

    @Test
    fun `a reading out of order does not break the window`() {
        val h = history()
        h.add(10 * sec, 100f)
        h.add(9 * sec, 99f)
        h.add(11 * sec, 101f)
        assertEquals(100f, h.altitudeAt(10 * sec)!!, 0.001f)
    }
}
