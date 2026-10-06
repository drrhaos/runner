package com.runner.academy.util

import com.runner.academy.data.PauseInterval
import com.runner.academy.data.PauseKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackPausesTest {

    private val pauses = listOf(
        PauseInterval(100L, 200L, PauseKind.MANUAL),
        PauseInterval(500L, 800L, PauseKind.AUTO)
    )

    @Test
    fun `no pauses overlap nothing`() {
        assertEquals(0L, TrackPauses.overlapMs(null, 0L, 1_000L))
        assertEquals(0L, TrackPauses.overlapMs(emptyList(), 0L, 1_000L))
    }

    @Test
    fun `a span covering whole pauses counts them fully, of any kind`() {
        assertEquals(100L + 300L, TrackPauses.overlapMs(pauses, 0L, 1_000L))
        assertEquals(300L, TrackPauses.overlapMs(pauses, 500L, 800L))
    }

    @Test
    fun `a span cutting a pause counts only the overlap`() {
        assertEquals(50L, TrackPauses.overlapMs(pauses, 150L, 400L))
        assertEquals(100L, TrackPauses.overlapMs(pauses, 400L, 600L))
        assertEquals(50L + 300L, TrackPauses.overlapMs(pauses, 150L, 900L))
        // Inside a pause
        assertEquals(100L, TrackPauses.overlapMs(pauses, 600L, 700L))
    }

    @Test
    fun `a span between or touching pauses overlaps nothing`() {
        assertEquals(0L, TrackPauses.overlapMs(pauses, 200L, 500L))
        assertEquals(0L, TrackPauses.overlapMs(pauses, 900L, 1_000L))
        // Reversed or empty span
        assertEquals(0L, TrackPauses.overlapMs(pauses, 800L, 500L))
        assertEquals(0L, TrackPauses.overlapMs(pauses, 600L, 600L))
    }

    @Test
    fun `overlapping pauses count their union once`() {
        // A manual pause on top of an auto-pause, and one touching it
        val overlapping = listOf(
            PauseInterval(500L, 800L, PauseKind.AUTO),
            PauseInterval(600L, 700L, PauseKind.MANUAL),
            PauseInterval(750L, 900L, PauseKind.MANUAL),
            PauseInterval(900L, 950L, PauseKind.AUTO)
        )

        assertEquals(450L, TrackPauses.overlapMs(overlapping, 0L, 1_000L))
        assertEquals(150L, TrackPauses.overlapMs(overlapping, 650L, 800L))
        // Order does not matter
        assertEquals(450L, TrackPauses.overlapMs(overlapping.reversed(), 0L, 1_000L))
    }

    @Test
    fun `a moment is inside a pause strictly between its ends`() {
        assertTrue(TrackPauses.isInside(pauses, 150L))
        assertTrue(TrackPauses.isInside(pauses, 501L))
        assertFalse(TrackPauses.isInside(pauses, 100L))
        assertFalse(TrackPauses.isInside(pauses, 800L))
        assertFalse(TrackPauses.isInside(pauses, 300L))
        assertFalse(TrackPauses.isInside(null, 150L))
    }

    @Test
    fun `the total of one kind sums its intervals`() {
        assertEquals(300L, TrackPauses.totalMs(pauses, PauseKind.AUTO))
        assertEquals(100L, TrackPauses.totalMs(pauses, PauseKind.MANUAL))
        assertEquals(0L, TrackPauses.totalMs(null, PauseKind.AUTO))
    }
}
