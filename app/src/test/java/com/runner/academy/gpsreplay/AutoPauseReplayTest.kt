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

/**
 * Auto-pause on the bench (release acceptance): moving time within ±2 s of the truth, the
 * distance does not depend on auto-pause, live = saved.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AutoPauseReplayTest {

    private val start = SyntheticRun.START_TIME
    private fun at(sec: Int) = start + sec * 1_000L

    private val withSteps = ReplaySettings(autoPause = true, stepsAvailable = true)
    private val gpsOnly = ReplaySettings(autoPause = true, stepsAvailable = false)

    /** 1 200 m at 3 m/s (400 s), [standSec] at a light, 1 200 m again: 800 s of moving. */
    private fun trafficLight(standSec: Int = 30, gapSec: IntRange? = null) = SyntheticRun(
        route = SyntheticRun.blockLoop(2),
        phases = listOf(Phase.Run(1_200.0, 3.0), Phase.Stand(standSec), Phase.Run(1_200.0, 3.0)),
        strideM = 1.0,
        gapSec = gapSec
    )

    private fun autoPauses(result: SessionReplayResult) = result.pauses.filter { it.kind == PauseKind.AUTO }

    private fun assertNear(message: String, expectedMs: Long, actualMs: Long, toleranceMs: Long = 2_000L) {
        assertTrue("$message: expected $expectedMs ± $toleranceMs ms, was $actualMs", abs(expectedMs - actualMs) <= toleranceMs)
    }

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

    /** Auto-pause only stops the moving clock: the same run without it covers the same distance. */
    private fun assertSameDistanceAsWithout(run: SyntheticRun, settings: ReplaySettings, result: SessionReplayResult,
                                            events: List<ReplayEvent> = emptyList()) {
        val without = SessionReplay.run(run, settings.copy(autoPause = false), events)
        assertEquals("distance", without.distanceM, result.distanceM, 0.01)
        assertEquals("elapsed", without.elapsedMs, result.elapsedMs)
    }

    @Test
    fun `a 30 s traffic light with steps stops the moving clock for the stand`() {
        val run = trafficLight()
        val result = SessionReplay.run(run, withSteps)

        assertEquals(830_000L, result.elapsedMs)
        assertNear("moving", 800_000L, result.movingMs)
        val pause = autoPauses(result).single()
        assertNear("pause start", at(400), pause.start)
        assertNear("pause end", at(430), pause.end)
        ReplayAsserts.assertDistance("live", 2_400.0, result.distanceM, 3.0)
        assertSameDistanceAsWithout(run, withSteps, result)
        assertLiveEqualsSaved(result)
    }

    @Test
    fun `a 30 s traffic light without steps stops the moving clock by GPS`() {
        val run = trafficLight()
        val result = SessionReplay.run(run, gpsOnly)

        assertNear("moving", 800_000L, result.movingMs)
        assertEquals(1, autoPauses(result).size)
        assertSameDistanceAsWithout(run, gpsOnly, result)
        assertLiveEqualsSaved(result)
    }

    @Test
    fun `a 1,2 m per s walk never auto-pauses, with steps or without`() {
        val run = SyntheticRun(
            route = SyntheticRun.blockLoop(2),
            phases = listOf(Phase.Run(1_000.0, 3.0), Phase.Walk(180, 1.2), Phase.Run(1_000.0, 3.0)),
            strideM = 1.0
        )
        for (settings in listOf(withSteps, gpsOnly)) {
            val result = SessionReplay.run(run, settings)

            assertTrue("steps ${settings.stepsAvailable}", result.pauses.isEmpty())
            assertEquals(result.elapsedMs, result.movingMs)
            assertLiveEqualsSaved(result)
        }
    }

    @Test
    fun `running uphill at 1,6 m per s never auto-pauses and resumes a GPS-only pause`() {
        val uphill = SyntheticRun(
            route = SyntheticRun.blockLoop(2),
            phases = listOf(Phase.Run(1_000.0, 3.0), Phase.Run(500.0, 1.6), Phase.Run(1_000.0, 3.0)),
            strideM = 1.0
        )
        for (settings in listOf(withSteps, gpsOnly)) {
            val result = SessionReplay.run(uphill, settings)
            assertTrue("steps ${settings.stepsAvailable}", result.pauses.isEmpty())
        }

        // Off the light straight into the climb: GPS alone sees 1,6 m/s and resumes
        val lightThenClimb = SyntheticRun(
            route = SyntheticRun.blockLoop(2),
            phases = listOf(Phase.Run(1_000.0, 3.0), Phase.Stand(40), Phase.Run(500.0, 1.6)),
            strideM = 1.0
        )
        val standStart = (1_000.0 / 3.0).toInt()
        val result = SessionReplay.run(lightThenClimb, gpsOnly)

        val pause = autoPauses(result).single()
        assertNear("pause end", at(standStart + 40), pause.end)
        assertNear("moving", result.elapsedMs - 40_000L, result.movingMs)
        assertLiveEqualsSaved(result)
    }

    @Test
    fun `an interval workout never auto-pauses`() {
        val result = SessionReplay.run(trafficLight(), withSteps.copy(intervals = true))

        assertEquals(result.elapsedMs, result.movingMs)
        assertTrue(result.pauses.isEmpty())
        assertLiveEqualsSaved(result)
    }

    @Test
    fun `without steps GPS lost before the stop does not auto-pause`() {
        // The fixes stop while still running (a tunnel) and come back after the light
        val run = trafficLight(standSec = 60, gapSec = 395..470)
        val result = SessionReplay.run(run, gpsOnly)

        assertTrue(result.pauses.isEmpty())
        assertEquals(result.elapsedMs, result.movingMs)
        assertLiveEqualsSaved(result)
    }

    @Test
    fun `without steps GPS lost during an auto-pause ends it, the time counts`() {
        // Standing at the light: 5 s of slow fixes, then the signal is gone until after the light
        val run = trafficLight(standSec = 60, gapSec = 405..470)
        val result = SessionReplay.run(run, gpsOnly)

        val pause = autoPauses(result).single()
        assertNear("pause start", at(400), pause.start)
        // Lost after 3 × 5 s without a good fix (the last at 404), seen by the 2 s watchdog
        assertNear("pause end at LOST", at(420), pause.end)
        assertNear("moving", result.elapsedMs - (pause.end - pause.start), result.movingMs, toleranceMs = 0L)
        assertSameDistanceAsWithout(run, gpsOnly, result)
        assertLiveEqualsSaved(result)
    }

    @Test
    fun `GPS silence with steps keeps running and pauses on the stop by steps`() {
        // Silent from 400 s; running on by steps until 600 s, standing 30 s, running again
        val run = SyntheticRun(
            route = SyntheticRun.blockLoop(3),
            phases = listOf(
                Phase.Run(1_200.0, 3.0),
                Phase.Run(600.0, 3.0),
                Phase.Stand(30),
                Phase.Run(1_200.0, 3.0)
            ),
            strideM = 1.0,
            gapSec = 400..700
        )
        val result = SessionReplay.run(run, withSteps)

        val pause = autoPauses(result).single()
        assertNear("pause start", at(600), pause.start)
        assertNear("pause end", at(630), pause.end)
        assertNear("moving", result.elapsedMs - 30_000L, result.movingMs)
        assertSameDistanceAsWithout(run, withSteps, result)
        assertLiveEqualsSaved(result)
    }

    @Test
    fun `a manual pause on an auto-pause closes it, a new one needs 10 s after Resume`() {
        val run = trafficLight(standSec = 60)
        val events = listOf(ReplayEvent.ManualPause(atSec = 420, sec = 30))
        val result = SessionReplay.run(run, withSteps, events)

        val auto = autoPauses(result).single()
        assertNear("auto start", at(400), auto.start)
        assertEquals("closed by the manual pause", at(420), auto.end)
        assertEquals(PauseInterval(at(420), at(450), PauseKind.MANUAL), result.pauses.single { it.kind == PauseKind.MANUAL })
        // 450..460 still standing, but under 10 s after Resume: counted as moving
        assertNear("moving", result.elapsedMs - 20_000L, result.movingMs)
        assertSameDistanceAsWithout(run, withSteps, result, events)
        assertLiveEqualsSaved(result)
    }

    @Test
    fun `Stop during an auto-pause keeps its tail out of moving`() {
        val result = SessionReplay.run(trafficLight(standSec = 60), withSteps, listOf(ReplayEvent.Stop(atSec = 440)))

        assertEquals(440_000L, result.elapsedMs)
        assertNear("moving", 400_000L, result.movingMs)
        val pause = autoPauses(result).single()
        assertEquals(at(440), pause.end)
        assertLiveEqualsSaved(result)
    }

    @Test
    fun `a restart during an auto-pause keeps it open and the same moving time`() {
        val run = trafficLight(standSec = 60)
        val reference = SessionReplay.run(run, withSteps)

        for (downSec in listOf(0, 10)) {
            val restarted = SessionReplay.run(run, withSteps, listOf(ReplayEvent.KillAndRestore(atSec = 420, downSec = downSec)))

            assertEquals("down $downSec s", reference.elapsedMs, restarted.elapsedMs)
            assertNear("down $downSec s: moving", reference.movingMs, restarted.movingMs)
            val pause = autoPauses(restarted).single()
            assertNear("down $downSec s: pause start", at(400), pause.start)
            assertNear("down $downSec s: pause end", at(460), pause.end)
            assertLiveEqualsSaved(restarted)
        }
    }

    @Test
    fun `auto-pause off changes nothing`() {
        val run = trafficLight()
        val result = SessionReplay.run(run, withSteps.copy(autoPause = false))

        assertEquals(result.elapsedMs, result.movingMs)
        assertTrue(result.pauses.isEmpty())
    }
}
