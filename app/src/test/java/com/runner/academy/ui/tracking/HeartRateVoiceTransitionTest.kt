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

    /**
     * Ticks every second from 0 to [toSec] (inclusive); a valid sample comes at each second where
     * [valid] holds. Collects what was said.
     */
    private fun HeartRateVoiceTimer.run(
        toSec: Int,
        valid: (Int) -> Boolean,
        manualPause: (Int) -> Boolean = { false }
    ): List<Pair<Int, HrAnnouncement>> {
        var lastValid: Long? = null
        return (0..toSec).mapNotNull { sec ->
            if (valid(sec)) lastValid = sec * s
            onTick(lastValid, sec * s, manualPause(sec))?.let { sec to it }
        }
    }

    @Test
    fun `timer - lost exactly 30 s after the last valid sample, connected on the next one`() {
        val timer = HeartRateVoiceTimer()
        // Last valid sample at 99 s; back at 140 s
        val said = timer.run(200, valid = { it !in 100..139 })

        assertEquals(listOf(99 + 30 to HrAnnouncement.LOST, 140 to HrAnnouncement.CONNECTED), said)
    }

    @Test
    fun `timer - a 29 second gap stays silent`() {
        val timer = HeartRateVoiceTimer()
        // Last valid at 99 s, the next at 128 s: 29 s without a value
        assertEquals(emptyList<Pair<Int, HrAnnouncement>>(), timer.run(200, valid = { it !in 100..127 }))
    }

    @Test
    fun `timer - a strap never worn says nothing`() {
        val timer = HeartRateVoiceTimer()

        assertEquals(emptyList<Pair<Int, HrAnnouncement>>(), timer.run(300, valid = { false }))
    }

    @Test
    fun `timer - the first connection, however late, is silent and arms the count`() {
        val timer = HeartRateVoiceTimer()
        // First value at 120 s (strap put on late), off again from 150 s
        val said = timer.run(300, valid = { it in 120..149 })

        assertEquals(listOf(149 + 30 to HrAnnouncement.LOST), said)
    }

    @Test
    fun `timer - nothing is said during a manual pause and the count restarts on resume`() {
        val timer = HeartRateVoiceTimer()
        // Strap off from 50 s; paused by hand 60..199 s; still off after the resume at 200 s
        val said = timer.run(260, valid = { it < 50 }, manualPause = { it in 60..199 })

        assertEquals(listOf(200 + 30 to HrAnnouncement.LOST), said)
    }

    @Test
    fun `timer - a value back during the pause is not announced as lost after the resume`() {
        val timer = HeartRateVoiceTimer()
        // Off from 50 s, paused 60..199 s, the strap is back at 150 s
        val said = timer.run(260, valid = { it < 50 || it >= 150 }, manualPause = { it in 60..199 })

        assertEquals(emptyList<Pair<Int, HrAnnouncement>>(), said)
    }
}
