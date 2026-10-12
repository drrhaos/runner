package com.runner.academy.ui.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HeartRateVoiceTransitionTest {

    @Test
    fun `a ten second drop is silent`() {
        assertNull(HeartRateVoiceTransition.announcement(hasValidData = false, sinceMs = 10_000L, announcedLost = false))
    }

    @Test
    fun `a drop of thirty seconds is lost, the return is connected`() {
        assertEquals(
            HrAnnouncement.LOST,
            HeartRateVoiceTransition.announcement(hasValidData = false, sinceMs = 31_000L, announcedLost = false)
        )
        // Said once
        assertNull(HeartRateVoiceTransition.announcement(hasValidData = false, sinceMs = 60_000L, announcedLost = true))
        assertEquals(
            HrAnnouncement.CONNECTED,
            HeartRateVoiceTransition.announcement(hasValidData = true, sinceMs = 0L, announcedLost = true)
        )
    }

    @Test
    fun `the first connection is silent`() {
        assertNull(HeartRateVoiceTransition.announcement(hasValidData = true, sinceMs = 0L, announcedLost = false))
    }

    // The service's per-second use

    private val s = 1_000L

    /** Ticks every second from [fromSec] to [toSec] (inclusive) and collects what was said. */
    private fun HeartRateVoiceTimer.run(
        fromSec: Int,
        toSec: Int,
        valid: (Int) -> Boolean,
        manualPause: (Int) -> Boolean = { false }
    ): List<Pair<Int, HrAnnouncement>> =
        (fromSec..toSec).mapNotNull { sec ->
            onTick(valid(sec), sec * s, manualPause(sec))?.let { sec to it }
        }

    @Test
    fun `timer - a ten second drop says nothing, a 31 second one is lost then connected`() {
        val timer = HeartRateVoiceTimer(startMs = 0L)
        val said = timer.run(0, 200, valid = { it !in 20..29 && it !in 100..130 })

        assertEquals(listOf(100 + 30 to HrAnnouncement.LOST, 131 to HrAnnouncement.CONNECTED), said)
    }

    @Test
    fun `timer - first connection after the start is silent`() {
        val timer = HeartRateVoiceTimer(startMs = 0L)

        assertEquals(emptyList<Pair<Int, HrAnnouncement>>(), timer.run(0, 60, valid = { it >= 10 }))
    }

    @Test
    fun `timer - nothing is said during a manual pause and the count restarts on resume`() {
        val timer = HeartRateVoiceTimer(startMs = 0L)
        // Strap off from 50 s; paused by hand 60..200 s; still off after the resume
        val said = timer.run(0, 260, valid = { it < 50 }, manualPause = { it in 60..199 })

        assertEquals(listOf(200 + 30 to HrAnnouncement.LOST), said)
    }
}
