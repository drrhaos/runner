package com.runner.academy.gpsreplay

import com.runner.academy.gpsreplay.SyntheticRun.Phase
import com.runner.academy.util.DerivationInput
import com.runner.academy.util.TrackCadence
import com.runner.academy.util.WorkoutDerivation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Average cadence on the bench (release acceptance): over moving time — pauses out, a stand
 * without auto-pause in; no step sensor gives null; live = saved.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CadenceReplayTest {

    private val withSteps = ReplaySettings(autoPause = true, stepsAvailable = true)

    /** 1 200 m at 3 m/s with a 1 m stride (180 steps/min), [standSec] at a light, 1 200 m again. */
    private fun trafficLight(standSec: Int = 60) = SyntheticRun(
        route = SyntheticRun.blockLoop(2),
        phases = listOf(Phase.Run(1_200.0, 3.0), Phase.Stand(standSec), Phase.Run(1_200.0, 3.0)),
        strideM = 1.0
    )

    /** The avgCadence the save path stores for the replayed workout. */
    private fun savedCadence(result: SessionReplayResult): Float? {
        val saved = result.savedWorkout
        return WorkoutDerivation.derive(DerivationInput(saved.trackData, saved.type, saved.duration)).avgCadence
    }

    /** The same average over the live session's points and pauses at Stop. */
    private fun liveCadence(result: SessionReplayResult): Float? = TrackCadence.average(result.livePoints, result.pauses)

    private fun assertLiveEqualsSaved(result: SessionReplayResult): Float? {
        val saved = savedCadence(result)
        val live = liveCadence(result)
        if (saved == null || live == null) {
            assertEquals("live = saved", live, saved)
        } else {
            assertEquals("live = saved", live, saved, 0.5f)
        }
        return saved
    }

    @Test
    fun `an auto-pause at a light is left out of the average`() {
        val result = SessionReplay.run(trafficLight(), withSteps)

        val cadence = assertLiveEqualsSaved(result)
        assertNotNull(cadence)
        assertEquals(180f, cadence!!, 1f)
    }

    @Test
    fun `standing at a light without auto-pause lowers the average`() {
        val result = SessionReplay.run(trafficLight(), withSteps.copy(autoPause = false))

        val cadence = assertLiveEqualsSaved(result)
        // 2 400 steps over 800 s of running and 60 s of standing
        assertEquals(180f * 800f / 860f, cadence!!, 1f)
    }

    @Test
    fun `a manual pause is left out of the average`() {
        val result = SessionReplay.run(
            trafficLight(standSec = 0),
            withSteps.copy(autoPause = false),
            listOf(ReplayEvent.ManualPause(atSec = 300, sec = 90))
        )

        val cadence = assertLiveEqualsSaved(result)
        assertEquals(180f, cadence!!, 1f)
    }

    @Test
    fun `without a step sensor there is no cadence`() {
        val result = SessionReplay.run(trafficLight(), withSteps.copy(stepsAvailable = false))

        assertNull(assertLiveEqualsSaved(result))
    }
}
