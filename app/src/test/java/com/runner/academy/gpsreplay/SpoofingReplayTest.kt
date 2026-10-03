package com.runner.academy.gpsreplay

import com.runner.academy.gpsreplay.ReplayAsserts.assertDistance
import com.runner.academy.gpsreplay.ReplayAsserts.assertNoTeleport
import com.runner.academy.gpsreplay.SyntheticRun.Spoof
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * False GPS signal (spoofing / jamming) scenarios — the acceptance spec for the false-signal
 * detector:
 *  - no kept point is a teleport (far off the true route);
 *  - a dropped stretch is bridged by a straight line (no step sensor yet), so distance lands
 *    within [TOLERANCE_PERCENT] of [SyntheticRun.expectedDistanceWithoutStepsM];
 *  - the episode leaves at most one break in the track (the dashed bridge), not a ragged
 *    run of gaps.
 *
 * Runs are one 1300 m block lap and long episodes span a corner, so "full path", "straight
 * line" and "nothing counted" differ by far more than the tolerance.
 * Scenarios the current filters do not handle yet are @Ignore'd with the reason.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SpoofingReplayTest {

    private fun lapWith(spoof: Spoof) = SyntheticRun(SyntheticRun.blockLoop(laps = 1), spoof = spoof)

    private fun assertHandled(run: SyntheticRun) {
        for ((name, result) in ReplayAsserts.bothPipelines(run.rawPoints())) {
            assertNoTeleport(name, run, result)
            assertDistance(name, run.expectedDistanceWithoutStepsM, result.distanceMeters, TOLERANCE_PERCENT)
            assertTrue("$name: ${result.gapCount} gaps for one episode", result.gapCount <= 1)
            assertTrue(
                "$name: the dropped stretch is a plain gap, not a bridge",
                result.points.filter { it.afterGap }.all { it.bridgeMeters != null }
            )
        }
    }

    @Test
    fun `short jump to the airport and back is dropped`() =
        assertHandled(lapWith(Spoof.Teleport(seconds = 200..210)))

    @Test
    fun `long stay at the airport is dropped and bridged`() =
        assertHandled(lapWith(Spoof.Teleport(seconds = 100..220)))

    @Test
    fun `run that starts at the airport begins at the first real fix`() =
        assertHandled(lapWith(Spoof.Teleport(seconds = 0..60)))

    @Test
    fun `frozen coordinates are bridged by a straight line`() =
        assertHandled(lapWith(Spoof.Frozen(seconds = 100..220)))

    @Test
    fun `drift at an impossible speed is dropped and bridged`() =
        assertHandled(lapWith(Spoof.Drift(seconds = 100..130, rateMps = 40.0)))

    @Ignore(
        "Not handled: a drift at running pace is plausible by coordinates alone (no jump, no " +
            "impossible speed, no frozen fixes) and is accepted (~190 m off); needs satellite " +
            "(GnssStatus) features calibrated on real diagnostics files"
    )
    @Test
    fun `drift at a running pace is dropped and bridged`() =
        assertHandled(lapWith(Spoof.Drift(seconds = 100..220, rateMps = 2.0)))

    /**
     * Real-world shape: two minutes without fixes, then two fixes ~13 km away with a reported
     * 28 m/s and a ~750 m higher altitude, then real fixes again. The silence stays a plain gap
     * (no distance), the far fixes are dropped.
     */
    @Test
    fun `far burst after a silence is dropped and the silence stays a gap`() {
        val run = SyntheticRun(
            SyntheticRun.blockLoop(laps = 1),
            gapSec = 100..219,
            spoof = Spoof.FarBurst(seconds = 220..221)
        )
        // Not counted: the silence and the two seconds of the burst
        val expected = run.routeLengthM - run.gapDistanceM - 2 * run.speedMps
        for ((name, result) in ReplayAsserts.bothPipelines(run.rawPoints())) {
            assertNoTeleport(name, run, result)
            assertDistance(name, expected, result.distanceMeters, TOLERANCE_PERCENT)
            assertEquals("$name gaps", 1, result.gapCount)
            assertTrue("$name: the silence is not bridged", result.points.none { it.bridgeMeters != null })
        }
    }

    private companion object {
        /** Detector acceptance: distance error within 5 %. */
        const val TOLERANCE_PERCENT = 5.0
    }
}
