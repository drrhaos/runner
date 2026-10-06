package com.runner.academy.ui.tracking

import com.runner.academy.service.AutoPauseEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutoPauseVoiceTransitionTest {

    @Test
    fun `an auto-pause is always announced`() {
        assertEquals(
            AutoPauseAnnouncement.PAUSED,
            AutoPauseVoiceTransition.announcement(AutoPauseEvent.Pause(1_000L), autoPauseDurationMs = 0L)
        )
    }

    @Test
    fun `resuming after 5 s or more is announced`() {
        assertEquals(
            AutoPauseAnnouncement.RESUMED,
            AutoPauseVoiceTransition.announcement(AutoPauseEvent.Resume(1_000L), autoPauseDurationMs = 5_000L)
        )
    }

    @Test
    fun `resuming after a short auto-pause is silent`() {
        assertNull(AutoPauseVoiceTransition.announcement(AutoPauseEvent.Resume(1_000L), autoPauseDurationMs = 4_999L))
    }
}
