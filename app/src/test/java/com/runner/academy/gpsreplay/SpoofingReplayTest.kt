package com.runner.academy.gpsreplay

import com.runner.academy.gpsreplay.ReplayAsserts.assertDistance
import com.runner.academy.gpsreplay.ReplayAsserts.assertNoTeleport
import com.runner.academy.gpsreplay.SyntheticRun.Spoof
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
        }
    }

    @Test
    fun `short jump to the airport and back is dropped`() =
        assertHandled(lapWith(Spoof.Teleport(seconds = 200..210)))

    @Ignore(
        "Not handled yet: after 20 s without an accepted fix (GpsFilter.GAP_RESUME_THRESHOLD_MS) " +
            "the spoofed fix is taken as a gap resume and anchors the track ~16.5 km off the route"
    )
    @Test
    fun `long stay at the airport is dropped and bridged`() =
        assertHandled(lapWith(Spoof.Teleport(seconds = 100..220)))

    @Ignore("Not handled yet: the first fix is accepted unchecked, so a spoofed start anchors the track")
    @Test
    fun `run that starts at the airport begins at the first real fix`() =
        assertHandled(lapWith(Spoof.Teleport(seconds = 0..60)))

    @Ignore(
        "Not handled yet: frozen fixes count as near-duplicates and the episode splits the track " +
            "into 6 gap segments (the distance itself ends up close to the straight line)"
    )
    @Test
    fun `frozen coordinates are bridged by a straight line`() =
        assertHandled(lapWith(Spoof.Frozen(seconds = 100..220)))

    @Ignore(
        "Not handled yet: the drifted fixes exceed the speed cap and are rejected, but once 20 s " +
            "pass without an accepted fix the next drifted fix is taken as a gap resume (~800 m off)"
    )
    @Test
    fun `drift at an impossible speed is dropped and bridged`() =
        assertHandled(lapWith(Spoof.Drift(seconds = 100..130, rateMps = 40.0)))

    @Ignore(
        "Not handled yet: a drift at running pace is plausible by coordinates alone and is " +
            "accepted (~190 m off); needs satellite (GnssStatus) features from real diagnostics files"
    )
    @Test
    fun `drift at a running pace is dropped and bridged`() =
        assertHandled(lapWith(Spoof.Drift(seconds = 100..220, rateMps = 2.0)))

    private companion object {
        /** Detector acceptance: distance error within 5 %. */
        const val TOLERANCE_PERCENT = 5.0
    }
}
