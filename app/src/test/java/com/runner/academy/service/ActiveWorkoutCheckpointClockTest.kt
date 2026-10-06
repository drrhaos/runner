package com.runner.academy.service

import com.google.gson.Gson
import com.runner.academy.data.PauseInterval
import com.runner.academy.data.PauseKind
import com.runner.academy.data.WorkoutType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ActiveWorkoutCheckpointClockTest {

    private val gson = Gson()
    private val t0 = 1_700_000_000_000L
    private fun at(sec: Int) = t0 + sec * 1_000L

    private fun roundTrip(checkpoint: ActiveWorkoutCheckpoint): ActiveWorkoutCheckpoint =
        gson.fromJson(gson.toJson(checkpoint), ActiveWorkoutCheckpoint::class.java)

    @Test
    fun `a release 2 checkpoint without the clock reads and keeps elapsed`() {
        val json = """{"isTracking":true,"startTime":$t0,"totalPauseDuration":30000,""" +
            """"currentTime":570000,"trackDataPoints":[],"rawTrackDataPoints":[]}"""

        val checkpoint = gson.fromJson(json, ActiveWorkoutCheckpoint::class.java)
        val session = checkpoint.toSession()
        val clock = SessionClock(session.clock)

        assertNull(checkpoint.clock)
        assertEquals(30_000L, session.clock.legacyManualPauseMs)
        // As before: now − start − totalPauseDuration
        assertEquals(at(600) - t0 - 30_000L, clock.elapsedMs(at(600)))
        assertEquals(clock.elapsedMs(at(600)), clock.movingMs(at(600)))
        assertEquals(570_000L, session.movingTime)
        assertTrue(clock.pauses().isEmpty())
    }

    @Test
    fun `a release 2 checkpoint on a manual pause keeps the time at the pause`() {
        val json = """{"isTracking":false,"isPaused":true,"startTime":$t0,"pauseTime":${at(300)},""" +
            """"totalPauseDuration":10000,"currentTime":290000,"trackDataPoints":[],"rawTrackDataPoints":[]}"""

        val clock = SessionClock(gson.fromJson(json, ActiveWorkoutCheckpoint::class.java).toSession().clock)

        assertEquals(290_000L, clock.elapsedMs(at(900)))
        clock.resumeManual(at(900))
        assertEquals(300_000L, clock.elapsedMs(at(910)))
        assertEquals(listOf(PauseInterval(at(300), at(900), PauseKind.MANUAL)), clock.pauses())
    }

    @Test
    fun `the clock rides the checkpoint, an open auto-pause stays open`() {
        val manager = WorkoutSessionManager()
        manager.startNewSession(now = t0)
        manager.pause(now = at(60))
        manager.resume(now = at(90))
        manager.enterAutoPause(at = at(200))
        manager.tickElapsedTime(now = at(230))
        val checkpoint = ActiveWorkoutCheckpoint.fromSession(
            manager.getSession(), WorkoutType.EASY_RUN, null, null, null,
            lastLocationTime = 0L, lastUpdateTime = 0L
        )

        val session = roundTrip(checkpoint).toSession()
        val clock = SessionClock(session.clock)

        assertTrue(clock.autoPaused)
        assertTrue(session.everAutoPaused)
        assertEquals(170_000L, session.movingTime)
        assertEquals(170_000L, clock.movingMs(at(500)))
        assertEquals(470_000L, clock.elapsedMs(at(500)))
        assertEquals(listOf(PauseInterval(at(60), at(90), PauseKind.MANUAL)), clock.pauses())
    }

    @Test
    fun `the session manager ticks elapsed and moving from the clock`() {
        val manager = WorkoutSessionManager()
        manager.startNewSession(now = t0)
        manager.tickElapsedTime(now = at(100))

        assertEquals(100_000L, manager.getSession().currentTime)
        assertEquals(100_000L, manager.getSession().movingTime)
        assertFalse(manager.getSession().autoPaused)

        manager.pause(now = at(100))
        manager.resume(now = at(160))
        manager.tickElapsedTime(now = at(200))

        assertEquals(140_000L, manager.getSession().currentTime)
        assertEquals(140_000L, manager.getSession().movingTime)
        // The legacy fields stay in step with the clock
        assertEquals(60_000L, manager.getSession().totalPauseDuration)
    }

    @Test
    fun `a backdated auto-pause rolls the moving time back in the same snapshot as the flag`() {
        val manager = WorkoutSessionManager()
        manager.startNewSession(now = t0)
        manager.tickElapsedTime(now = at(70))
        val seen = mutableListOf<com.runner.academy.data.WorkoutSession>()
        manager.onSessionChanged = { seen += it }

        manager.enterAutoPause(at = at(60), now = at(70))

        val paused = seen.single()
        assertTrue(paused.autoPaused)
        assertEquals(60_000L, paused.movingTime)
        assertEquals(70_000L, paused.currentTime)

        manager.exitAutoPause(at = at(100), now = at(102))
        assertFalse(manager.getSession().autoPaused)
        assertEquals(62_000L, manager.getSession().movingTime)
        assertEquals(102_000L, manager.getSession().currentTime)
    }

    @Test
    fun `stop freezes the session time at the stop`() {
        val manager = WorkoutSessionManager()
        manager.startNewSession(now = t0)
        manager.enterAutoPause(at = at(50))
        manager.stop(now = at(80))

        val session = manager.getSession()
        assertEquals(80_000L, session.currentTime)
        assertEquals(50_000L, session.movingTime)
        assertEquals(at(80), session.clock.stoppedAt)
    }
}
