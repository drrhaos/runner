package com.runner.academy.service

import com.google.gson.Gson
import com.runner.academy.data.PauseInterval
import com.runner.academy.data.PauseKind
import com.runner.academy.data.SessionClockState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionClockTest {

    private val t0 = 1_700_000_000_000L
    private fun at(sec: Int) = t0 + sec * 1_000L

    private fun started() = SessionClock().apply { start(t0) }

    @Test
    fun `without pauses elapsed and moving are the time since the start`() {
        val clock = started()

        assertEquals(90_000L, clock.elapsedMs(at(90)))
        assertEquals(90_000L, clock.movingMs(at(90)))
        assertTrue(clock.pauses().isEmpty())
        assertFalse(clock.everAutoPaused)
    }

    @Test
    fun `a manual pause stops both clocks and is recorded as MANUAL`() {
        val clock = started()
        clock.pauseManual(at(60))

        assertEquals(60_000L, clock.elapsedMs(at(100)))
        clock.resumeManual(at(100))

        assertEquals(80_000L, clock.elapsedMs(at(120)))
        assertEquals(80_000L, clock.movingMs(at(120)))
        assertEquals(listOf(PauseInterval(at(60), at(100), PauseKind.MANUAL)), clock.pauses())
    }

    @Test
    fun `an auto-pause stops only the moving clock`() {
        val clock = started()
        clock.enterAutoPause(at(60))

        assertTrue(clock.autoPaused)
        assertEquals(90_000L, clock.elapsedMs(at(90)))
        assertEquals(60_000L, clock.movingMs(at(90)))

        clock.exitAutoPause(at(90))

        assertFalse(clock.autoPaused)
        assertTrue(clock.everAutoPaused)
        assertEquals(120_000L, clock.elapsedMs(at(120)))
        assertEquals(90_000L, clock.movingMs(at(120)))
        assertEquals(listOf(PauseInterval(at(60), at(90), PauseKind.AUTO)), clock.pauses())
    }

    @Test
    fun `a manual pause closes an open auto-pause and moving does not grow`() {
        val clock = started()
        clock.enterAutoPause(at(60))
        clock.pauseManual(at(80))

        assertFalse(clock.autoPaused)
        assertEquals(60_000L, clock.movingMs(at(80)))
        assertEquals(60_000L, clock.movingMs(at(200)))
        assertEquals(80_000L, clock.elapsedMs(at(200)))

        clock.resumeManual(at(200))

        assertEquals(70_000L, clock.movingMs(at(210)))
        assertEquals(
            listOf(
                PauseInterval(at(60), at(80), PauseKind.AUTO),
                PauseInterval(at(80), at(200), PauseKind.MANUAL)
            ),
            clock.pauses()
        )
    }

    @Test
    fun `an auto-pause does not start during a manual pause`() {
        val clock = started()
        clock.pauseManual(at(60))
        clock.enterAutoPause(at(70))

        assertFalse(clock.autoPaused)
        assertFalse(clock.everAutoPaused)
        clock.resumeManual(at(100))
        assertEquals(80_000L, clock.movingMs(at(120)))
    }

    @Test
    fun `an auto-pause does not start before the last resume`() {
        val clock = started()
        clock.pauseManual(at(60))
        clock.resumeManual(at(100))
        // Detector backdates the start to before the resume
        clock.enterAutoPause(at(90))
        clock.exitAutoPause(at(110))

        assertEquals(listOf(PauseKind.MANUAL, PauseKind.AUTO), clock.pauses().map { it.kind })
        assertEquals(PauseInterval(at(100), at(110), PauseKind.AUTO), clock.pauses().last())
        assertEquals(70_000L, clock.movingMs(at(120)))
    }

    @Test
    fun `an auto-pause does not start before the last auto-pause ended`() {
        val clock = started()
        clock.enterAutoPause(at(30))
        clock.exitAutoPause(at(40))
        clock.enterAutoPause(at(35))

        assertEquals(at(40), clock.state.autoPausedAt)
    }

    @Test
    fun `stop in an auto-pause keeps its tail out of moving`() {
        val clock = started()
        clock.enterAutoPause(at(60))
        clock.stop(at(90))

        assertEquals(90_000L, clock.elapsedMs(at(90)))
        assertEquals(60_000L, clock.movingMs(at(90)))
        // Frozen after the stop
        assertEquals(90_000L, clock.elapsedMs(at(500)))
        assertEquals(60_000L, clock.movingMs(at(500)))
        assertEquals(listOf(PauseInterval(at(60), at(90), PauseKind.AUTO)), clock.pauses())
    }

    @Test
    fun `stop on a manual pause closes it and keeps the time at the pause`() {
        val clock = started()
        clock.pauseManual(at(60))
        clock.stop(at(300))

        assertEquals(60_000L, clock.elapsedMs(at(400)))
        assertEquals(60_000L, clock.movingMs(at(400)))
        assertEquals(listOf(PauseInterval(at(60), at(300), PauseKind.MANUAL)), clock.pauses())
    }

    @Test
    fun `nothing changes after the stop`() {
        val clock = started()
        clock.stop(at(60))
        clock.pauseManual(at(70))
        clock.enterAutoPause(at(80))

        assertEquals(60_000L, clock.elapsedMs(at(100)))
        assertTrue(clock.pauses().isEmpty())
    }

    @Test
    fun `a state restored mid auto-pause keeps it open and the same moving time`() {
        val clock = started()
        clock.enterAutoPause(at(60))
        val moving = clock.movingMs(at(70))

        val restored = SessionClock(Gson().let { it.fromJson(it.toJson(clock.state), SessionClockState::class.java) })

        assertTrue(restored.autoPaused)
        assertEquals(moving, restored.movingMs(at(70)))
        assertEquals(moving, restored.movingMs(at(170)))
        restored.exitAutoPause(at(170))
        assertEquals(70_000L, restored.movingMs(at(180)))
    }

    @Test
    fun `the legacy manual pause time counts against elapsed`() {
        val clock = SessionClock(SessionClockState(startedAt = t0, legacyManualPauseMs = 30_000L))

        assertEquals(70_000L, clock.elapsedMs(at(100)))
        assertEquals(70_000L, clock.movingMs(at(100)))
        assertTrue(clock.pauses().isEmpty())
    }

    @Test
    fun `the state survives a Gson round trip, missing fields take defaults`() {
        val clock = started()
        clock.pauseManual(at(10))
        clock.resumeManual(at(20))
        clock.enterAutoPause(at(30))
        clock.exitAutoPause(at(40))
        clock.enterAutoPause(at(50))
        val gson = Gson()

        val back = gson.fromJson(gson.toJson(clock.state), SessionClockState::class.java)
        val empty = gson.fromJson("{}", SessionClockState::class.java)

        assertEquals(clock.state, back)
        assertEquals(SessionClockState(), empty)
    }
}
