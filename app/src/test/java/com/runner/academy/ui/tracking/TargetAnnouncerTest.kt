package com.runner.academy.ui.tracking

import com.runner.academy.data.IntervalLive
import com.runner.academy.voice.TargetState
import com.runner.academy.voice.TargetVerdict
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetAnnouncerTest {

    private val announcer = TargetAnnouncer()

    private fun live(sec: Int, due: Boolean = false, voiced: Boolean = false) = IntervalLive(
        segmentIndex = 1, segmentProgress = 0.5f, segmentElapsedMs = sec * 1_000L, completed = false,
        target = TargetState(TargetVerdict.TOO_SLOW, value = 4.8f), hintDue = due, hintVoiced = voiced
    )

    @Test
    fun `a due hint the voice did not say is announced once, with TalkBack on`() {
        val due = live(31, due = true)
        assertTrue(announcer.shouldAnnounce(due, talkBackOn = true))
        // The same tick's session again (a fix came)
        assertFalse(announcer.shouldAnnounce(due, talkBackOn = true))
        assertFalse(announcer.shouldAnnounce(live(32), talkBackOn = true))
        assertTrue(announcer.shouldAnnounce(live(61, due = true), talkBackOn = true))
    }

    @Test
    fun `not when the voice said it, TalkBack is off, no hint is due or there are no segments`() {
        assertFalse(announcer.shouldAnnounce(live(31, due = true, voiced = true), talkBackOn = true))
        assertFalse(announcer.shouldAnnounce(live(31, due = true), talkBackOn = false))
        assertFalse(announcer.shouldAnnounce(live(31), talkBackOn = true))
        assertFalse(announcer.shouldAnnounce(null, talkBackOn = true))
    }
}
