package com.runner.academy.gpsreplay

import com.runner.academy.data.PauseInterval
import com.runner.academy.data.PauseKind
import com.runner.academy.gpsreplay.SyntheticRun.Phase
import com.runner.academy.util.TrackDataJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

/** The session clock on the bench: pauses, Stop and process restarts, live = saved. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SessionReplayTest {

    private val start = SyntheticRun.START_TIME
    private fun at(sec: Int) = start + sec * 1_000L

    /** 400 s running, 120 s manual pause, 400 s running: 2 400 m in 800 s of time. */
    private val pausedRun = SyntheticRun(
        route = SyntheticRun.blockLoop(2),
        phases = listOf(Phase.Run(1_200.0, 3.0), Phase.ManualPause(120), Phase.Run(1_200.0, 3.0))
    )

    /** 2 600 m at 3.25 m/s = 800 s, no pauses. */
    private val plainRun = SyntheticRun(route = SyntheticRun.blockLoop(2), speedMps = 3.25)

    private fun assertLiveEqualsSaved(result: SessionReplayResult) {
        val saved = result.savedWorkout
        assertEquals("duration", result.elapsedMs, saved.duration)
        assertEquals("movingDuration", result.movingMs, saved.movingDuration)
        assertEquals("pauses", result.pauses, TrackDataJson.parse(saved.trackData)!!.pauses)
        val savedM = saved.distance * 1000.0
        assertTrue(
            "live %.1f m vs saved %.1f m".format(result.distanceM, savedM),
            abs(result.distanceM - savedM) <= result.distanceM * 0.005
        )
    }

    @Test
    fun `a manual pause phase has no fixes and no step growth`() {
        val run = pausedRun.copy(strideM = 1.0)
        val points = run.rawPoints()

        assertEquals(920, run.durationSec)
        assertEquals(listOf(400 to 520), run.manualPauseSeconds)
        assertTrue(points.none { it.timestamp > at(400) && it.timestamp < at(520) })
        assertEquals(run.stepsAt(at(401))!!.first, run.stepsAt(at(519))!!.first)
        assertEquals(2_400, run.stepsAt(at(920))!!.first)
    }

    @Test
    fun `a manual pause mid-run keeps moving equal to elapsed and is saved as MANUAL`() {
        val result = SessionReplay.run(pausedRun)

        assertEquals(800_000L, result.elapsedMs)
        assertEquals(result.elapsedMs, result.movingMs)
        assertEquals(listOf(PauseInterval(at(400), at(520), PauseKind.MANUAL)), result.pauses)
        ReplayAsserts.assertDistance("live", 2_400.0, result.distanceM, 3.0)
        assertLiveEqualsSaved(result)
    }

    @Test
    fun `a manual pause pressed during a plain run behaves the same`() {
        val result = SessionReplay.run(plainRun, events = listOf(ReplayEvent.ManualPause(atSec = 300, sec = 60)))

        assertEquals(740_000L, result.elapsedMs)
        assertEquals(result.elapsedMs, result.movingMs)
        assertEquals(listOf(PauseInterval(at(300), at(360), PauseKind.MANUAL)), result.pauses)
        assertLiveEqualsSaved(result)
    }

    @Test
    fun `Stop on a manual pause keeps the time at the pause`() {
        val result = SessionReplay.run(pausedRun, events = listOf(ReplayEvent.Stop(atSec = 460)))

        assertEquals(400_000L, result.elapsedMs)
        assertEquals(400_000L, result.movingMs)
        assertEquals(listOf(PauseInterval(at(400), at(460), PauseKind.MANUAL)), result.pauses)
        ReplayAsserts.assertDistance("live", 1_200.0, result.distanceM, 3.0)
        assertLiveEqualsSaved(result)
    }

    @Test
    fun `an instant restart mid-run changes nothing`() {
        val reference = SessionReplay.run(plainRun)
        val restarted = SessionReplay.run(plainRun, events = listOf(ReplayEvent.KillAndRestore(atSec = 300)))

        assertEquals(800_000L, reference.elapsedMs)
        assertEquals(reference.elapsedMs, restarted.elapsedMs)
        assertEquals(reference.movingMs, restarted.movingMs)
        assertEquals(reference.distanceM, restarted.distanceM, 1.0)
        assertLiveEqualsSaved(restarted)
    }

    @Test
    fun `the downtime of a dead process counts in elapsed and moving, as before`() {
        val result = SessionReplay.run(plainRun, events = listOf(ReplayEvent.KillAndRestore(atSec = 300, downSec = 40)))

        assertEquals(800_000L, result.elapsedMs)
        assertEquals(result.elapsedMs, result.movingMs)
        assertTrue(result.pauses.isEmpty())
        assertLiveEqualsSaved(result)
    }

    @Test
    fun `a restart on a manual pause keeps the pause and the time`() {
        val result = SessionReplay.run(
            pausedRun,
            events = listOf(ReplayEvent.KillAndRestore(atSec = 450, downSec = 30))
        )

        assertEquals(800_000L, result.elapsedMs)
        assertEquals(result.elapsedMs, result.movingMs)
        assertEquals(listOf(PauseInterval(at(400), at(520), PauseKind.MANUAL)), result.pauses)
        ReplayAsserts.assertDistance("live", 2_400.0, result.distanceM, 3.0)
        assertLiveEqualsSaved(result)
    }

    @Test
    fun `a restart with steps continues the step count`() {
        val run = pausedRun.copy(strideM = 1.0)
        val result = SessionReplay.run(run, events = listOf(ReplayEvent.KillAndRestore(atSec = 200, downSec = 10)))

        assertEquals(800_000L, result.elapsedMs)
        val steps = TrackDataJson.parse(result.savedWorkout.trackData)!!.points.mapNotNull { it.steps }
        assertTrue("steps never go back", steps.zipWithNext().all { (a, b) -> b >= a })
        assertLiveEqualsSaved(result)
    }
}
