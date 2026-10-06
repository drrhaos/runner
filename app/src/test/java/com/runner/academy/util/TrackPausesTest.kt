package com.runner.academy.util

import com.runner.academy.data.PauseInterval
import com.runner.academy.data.PauseKind
import org.junit.Assert.assertEquals
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
    fun `the total of one kind sums its intervals`() {
        assertEquals(300L, TrackPauses.totalMs(pauses, PauseKind.AUTO))
        assertEquals(100L, TrackPauses.totalMs(pauses, PauseKind.MANUAL))
        assertEquals(0L, TrackPauses.totalMs(null, PauseKind.AUTO))
    }
}
