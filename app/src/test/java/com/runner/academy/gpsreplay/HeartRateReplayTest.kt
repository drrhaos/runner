package com.runner.academy.gpsreplay

import com.runner.academy.gpsreplay.SyntheticRun.Phase
import com.runner.academy.util.DerivationInput
import com.runner.academy.util.Derived
import com.runner.academy.util.TrackDataJson
import com.runner.academy.util.TrackHeartRate
import com.runner.academy.util.WorkoutDerivation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Heart rate on the bench: the values a sensor would give are put straight on the fixes (no
 * sensor modelled) and go through the session, the save path and the derivation. The average
 * is over the moving time with a value — gaps and auto-pauses out; one spike never reaches the
 * maximum; live = saved.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class HeartRateReplayTest {

    private val settings = ReplaySettings(autoPause = true)

    /** 1 200 m at 3 m/s, [standSec] at a light, 1 200 m again; the heart rate by [hrAt]. */
    private fun trafficLight(standSec: Int = 60, hrAt: (Long) -> Int?) = SyntheticRun(
        route = SyntheticRun.blockLoop(2),
        phases = listOf(Phase.Run(1_200.0, 3.0), Phase.Stand(standSec), Phase.Run(1_200.0, 3.0)),
        strideM = 1.0,
        hrAt = hrAt
    )

    /** What the save path stores for the replayed workout. */
    private fun saved(result: SessionReplayResult): Derived {
        val workout = result.savedWorkout
        return WorkoutDerivation.derive(DerivationInput(workout.trackData, workout.type, workout.duration))
    }

    /** The saved average, checked against the same average over the live session at Stop. */
    private fun assertLiveEqualsSaved(result: SessionReplayResult): Int? {
        val saved = saved(result).avgHeartRate
        val live = TrackHeartRate.average(result.livePoints, result.pauses)
        if (saved == null || live == null) {
            assertEquals("live = saved", live, saved)
        } else {
            assertEquals("live = saved", live.toFloat(), saved.toFloat(), 1f)
        }
        return saved
    }

    private fun sec(tMs: Long) = (tMs / 1_000L).toInt()

    @Test
    fun `a steady 150 averages 150`() {
        val result = SessionReplay.run(trafficLight(standSec = 0) { 150 }, settings)

        val avg = assertLiveEqualsSaved(result)
        assertNotNull(avg)
        assertEquals(150f, avg!!.toFloat(), 1f)
        assertEquals(150, saved(result).maxHeartRate)
    }

    @Test
    fun `a minute without contact leaves its points empty and out of the average`() {
        // The strap loses contact 200..259 s; around it 150, and it would not matter what
        val result = SessionReplay.run(trafficLight(standSec = 0) { t -> if (sec(t) in 200..259) null else 150 }, settings)

        val points = TrackDataJson.parse(result.savedWorkout.trackData)!!.points
        val start = points.first().timestamp
        val inGap = points.filter { (it.timestamp - start) / 1_000L in 201L..258L }
        assertTrue(inGap.isNotEmpty())
        assertTrue(inGap.all { it.heartRate == null })
        assertTrue(points.count { it.heartRate != null } > points.size / 2)
        assertEquals(150f, assertLiveEqualsSaved(result)!!.toFloat(), 1f)
    }

    @Test
    fun `an auto-pause at a light is left out of the average`() {
        // The heart rate drops to 110 while standing at the light
        val hr: (Long) -> Int = { t -> if (sec(t) in 400..459) 110 else 150 }

        val result = SessionReplay.run(trafficLight(hrAt = hr), settings)

        assertTrue("the light was an auto-pause", result.pauses.isNotEmpty())
        assertEquals(150f, assertLiveEqualsSaved(result)!!.toFloat(), 1f)
    }

    @Test
    fun `a single 220 spike does not reach the maximum`() {
        // A dry strap at the start reads 220 once
        val result = SessionReplay.run(trafficLight(standSec = 0) { t -> if (sec(t) == 30) 220 else 150 }, settings)

        val points = TrackDataJson.parse(result.savedWorkout.trackData)!!.points
        assertTrue("the spike is in the track", points.any { it.heartRate == 220 })
        assertEquals(150, saved(result).maxHeartRate)
        assertEquals(150f, assertLiveEqualsSaved(result)!!.toFloat(), 1f)
    }
}
