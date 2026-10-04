package com.runner.academy.gpsreplay

import com.runner.academy.data.LocationSource
import com.runner.academy.gpsreplay.ReplayAsserts.assertDistance
import com.runner.academy.gpsreplay.ReplayAsserts.assertNoTeleport
import com.runner.academy.gpsreplay.SyntheticRun.Spoof
import com.runner.academy.util.StepDistanceEstimator
import com.runner.academy.util.StrideModel
import com.runner.academy.util.TrackGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Release-2 acceptance with a step sensor: a false-signal stretch is dropped and its distance
 * counted from steps, so the total lands within [TOLERANCE_PERCENT] of the **true route**
 * (not of the straight-line estimate), with no teleport kept and the live total equal to the
 * saved one. The runner has a true stride of 1.1 m at 165 spm.
 *
 * Without steps the expectations of [SpoofingReplayTest] hold unchanged.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class StepBridgeReplayTest {

    /** A model that already learned the runner's stride. */
    private val trained = StepDistanceEstimator { steps, _ -> steps * STRIDE_M.toFloat() }

    /** Untrained prior for a 175 cm runner (≈1.14 m at 165 spm). */
    private val prior = StrideModel.fromHeight(175f).frozenEstimator()

    private fun lapWith(spoof: Spoof, gapSec: IntRange? = null) = SyntheticRun(
        SyntheticRun.blockLoop(laps = 1),
        speedMps = STRIDE_M * CADENCE / 60.0,
        spoof = spoof,
        gapSec = gapSec,
        strideM = STRIDE_M
    )

    private fun bothPipelines(run: SyntheticRun, estimator: StepDistanceEstimator?): List<Pair<String, ReplayResult>> {
        val raw = run.rawPoints()
        return listOf(
            "live" to GpsReplay.live(raw, stepDistance = estimator),
            "saved" to GpsReplay.saved(raw, stepDistance = estimator)
        )
    }

    private fun assertCountedBySteps(run: SyntheticRun, estimator: StepDistanceEstimator = trained) {
        val results = bothPipelines(run, estimator)
        for ((name, result) in results) {
            assertNoTeleport(name, run, result)
            assertDistance(name, run.routeLengthM, result.distanceMeters, TOLERANCE_PERCENT)
            assertTrue("$name: ${result.gapCount} breaks for one episode", result.gapCount <= 1)
        }
        val (live, saved) = results.map { it.second }
        assertEquals("live and saved totals agree", saved.distanceMeters, live.distanceMeters, 1.0)
        assertEquals(
            "the saved total is what its points add up to",
            TrackGeometry.totalDistanceMeters(saved.points).toDouble(),
            saved.distanceMeters,
            0.01
        )
    }

    private fun bridgeOf(result: ReplayResult) = result.points.single { it.afterGap }

    @Test
    fun `long stay at the airport is counted by steps round the corner`() {
        val run = lapWith(Spoof.Teleport(seconds = 100..220))
        assertCountedBySteps(run)
        for ((name, result) in bothPipelines(run, trained)) {
            val bridge = bridgeOf(result)
            assertEquals("$name source", LocationSource.PEDOMETER.name, bridge.source)
        }
        // Without steps the same run only gets the straight line across the corner
        val plain = GpsReplay.saved(run.rawPoints())
        assertDistance("straight line", run.expectedDistanceWithoutStepsM, plain.distanceMeters, TOLERANCE_PERCENT)
        assertTrue(
            "steps are closer to the truth than the straight line",
            run.routeLengthM - GpsReplay.saved(run.rawPoints(), stepDistance = trained).distanceMeters <
                run.routeLengthM - plain.distanceMeters
        )
    }

    @Test
    fun `frozen coordinates are counted by steps`() {
        val run = lapWith(Spoof.Frozen(seconds = 100..220))
        assertCountedBySteps(run)
        for ((name, result) in bothPipelines(run, trained)) {
            assertEquals("$name source", LocationSource.PEDOMETER.name, bridgeOf(result).source)
        }
    }

    @Test
    fun `drift at an impossible speed is counted by steps`() =
        assertCountedBySteps(lapWith(Spoof.Drift(seconds = 100..130, rateMps = 40.0)))

    @Test
    fun `short jump to the airport and back stays an ordinary step`() =
        assertCountedBySteps(lapWith(Spoof.Teleport(seconds = 200..210)))

    @Test
    fun `a run that starts at the airport counts its start by steps`() {
        val run = lapWith(Spoof.Teleport(seconds = 0..60))
        assertCountedBySteps(run)
        for ((name, result) in bothPipelines(run, trained)) {
            val start = result.points.first()
            assertTrue("$name: track begins at a real fix", run.offRouteMeters(start) < ReplayAsserts.MAX_OFF_ROUTE_M)
            assertTrue("$name: lead-in counted", TrackGeometry.leadInMeters(result.points) > 150f)
        }
    }

    @Test
    fun `the untrained height prior stays within tolerance`() {
        assertCountedBySteps(lapWith(Spoof.Teleport(seconds = 100..220)), prior)
        assertCountedBySteps(lapWith(Spoof.Teleport(seconds = 0..60)), prior)
    }

    @Test
    fun `a silence is counted by steps and the far burst after it is dropped`() {
        // Owner's decision (2026-10-04): a silence (tunnel, jamming, GNSS off under battery
        // saver) is bridged by steps; the burst of far fixes after it is still dropped
        val run = lapWith(Spoof.FarBurst(seconds = 220..221), gapSec = 100..219)
        for ((name, result) in bothPipelines(run, trained)) {
            assertNoTeleport(name, run, result)
            assertDistance(name, run.routeLengthM, result.distanceMeters, TOLERANCE_PERCENT)
            val bridge = result.points.single { TrackGeometry.isBridgeStep(it) }
            assertEquals("$name: bridged by steps", LocationSource.PEDOMETER.name, bridge.source)
        }
    }

    @Test
    fun `an estimator without steps in the points changes nothing`() {
        val run = SyntheticRun(SyntheticRun.blockLoop(laps = 1), spoof = Spoof.Teleport(seconds = 100..220))
        val raw = run.rawPoints()
        assertEquals(
            GpsReplay.saved(raw).distanceMeters,
            GpsReplay.saved(raw, stepDistance = trained).distanceMeters,
            0.0
        )
        assertEquals(
            GpsReplay.live(raw).distanceMeters,
            GpsReplay.live(raw, stepDistance = trained).distanceMeters,
            0.0
        )
    }

    private companion object {
        const val TOLERANCE_PERCENT = 5.0
        const val STRIDE_M = 1.1
        const val CADENCE = 165.0
    }
}
