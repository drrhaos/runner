package com.runner.academy.service

import com.runner.academy.data.GpsStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The service's auto-pause glue; mono and wall are the same clock here. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AutoPauseControllerTest {

    private val t0 = 1_700_000_000_000L
    private fun at(sec: Int) = t0 + sec * 1_000L

    private val manager = WorkoutSessionManager().apply { startNewSession(now = t0) }
    private val controller = AutoPauseController().apply { reset() }

    /** One second: a fix at [speed] (if any) then the timer tick. */
    private fun second(sec: Int, steps: Int?, speed: Float? = null, enabled: Boolean = true): AutoPauseTransition? {
        if (speed != null) controller.onFix(at(sec) - 500L, speed, usable = true)
        return controller.tick(manager, enabled, steps, nowMono = at(sec), nowWall = at(sec))
    }

    @Test
    fun `turned on while standing, the pause starts no earlier than the switch`() {
        (1..20).forEach { assertNull(second(it, null, speed = 3f, enabled = false)) }
        (21..40).forEach { assertNull(second(it, null, speed = 0.2f, enabled = false)) }

        val transitions = (41..60).mapNotNull { second(it, null, speed = 0.2f) }

        val pause = transitions.single()
        assertTrue(pause.event is AutoPauseEvent.Pause)
        assertTrue("starts at ${pause.event.atMono - t0}", pause.event.atMono >= at(41) - 500L)
        assertTrue(manager.getSession().autoPaused)
    }

    @Test
    fun `turned on again with steps, the old last step does not backdate the pause`() {
        (1..10).forEach { second(it, it * 3, speed = 3f) }
        // Off from 11 s; the steps stop at 30 s
        (11..30).forEach { second(it, it * 3, speed = 3f, enabled = false) }
        (31..40).forEach { second(it, 90, speed = 0f, enabled = false) }

        val pause = (41..60).mapNotNull { second(it, 90, speed = 0f) }.single()

        assertTrue("starts at ${pause.event.atMono - t0}", pause.event.atMono >= at(41))
    }

    @Test
    fun `turned off during an auto-pause ends it now, silently`() {
        (1..10).forEach { second(it, it * 3, speed = 3f) }
        (11..25).forEach { second(it, 30, speed = 0f) }
        assertTrue(manager.getSession().autoPaused)

        val exit = second(26, 30, speed = 0f, enabled = false)

        assertNotNull(exit)
        assertEquals(AutoPauseEvent.Resume(at(26)), exit!!.event)
        assertFalse(exit.announce)
        assertFalse(manager.getSession().autoPaused)
    }

    @Test
    fun `a refused event leaves the detector following the clock`() {
        (1..10).forEach { second(it, it * 3, speed = 3f) }
        // The clock is already auto-paused (from elsewhere): the detector's Pause is refused
        manager.enterAutoPause(at(10), at(11))
        val refused = (11..25).mapNotNull { second(it, 30, speed = 0f) }
        assertTrue(refused.isEmpty())

        // The detector now knows it is paused: the first steps resume the clock
        val resume = second(26, 33, speed = 0f)
        assertEquals(AutoPauseEvent.Resume(at(26)), resume?.event)
        assertEquals(at(26) - at(10), resume!!.closedAutoPauseMs)
        assertFalse(manager.getSession().autoPaused)
    }

    @Test
    fun `restored into an auto-pause the first resume is silent, later ones speak`() {
        manager.enterAutoPause(at(5), at(15))
        val restored = AutoPauseController().apply { reset(autoPaused = true, restored = true) }
        fun tick(sec: Int, steps: Int) = restored.tick(manager, true, steps, at(sec), at(sec))

        assertNull(tick(100, 300))
        val first = tick(101, 303)!!
        assertTrue(first.event is AutoPauseEvent.Resume)
        assertFalse(first.announce)

        val pause = (102..115).mapNotNull { tick(it, 303) }.single()
        assertTrue(pause.announce)
        val second = tick(130, 306)!!
        assertTrue(second.event is AutoPauseEvent.Resume)
        assertTrue(second.announce)
        assertEquals(at(130) - at(101), second.closedAutoPauseMs)
    }

    @Test
    fun `lost and denied GPS both count as lost`() {
        assertTrue(AutoPauseController.isGpsLost(GpsStatus.LOST))
        assertTrue(AutoPauseController.isGpsLost(GpsStatus.DENIED))
        assertFalse(AutoPauseController.isGpsLost(GpsStatus.FOUND))
        assertFalse(AutoPauseController.isGpsLost(GpsStatus.UNRELIABLE))
    }
}
