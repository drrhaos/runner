package com.runner.academy.gpsreplay

import com.runner.academy.data.WorkoutType
import org.junit.Assert.assertTrue
import kotlin.math.abs

/** Assertions shared by the replay-bench tests. */
object ReplayAsserts {

    /** A kept point farther than this from the true route counts as a teleport. */
    const val MAX_OFF_ROUTE_M = 30.0

    /** Raw fixes replayed through the live and the save pipeline, labelled for messages. */
    fun bothPipelines(raw: List<com.runner.academy.data.TrackPoint>, type: WorkoutType = WorkoutType.EASY_RUN) =
        listOf("live" to GpsReplay.live(raw, type), "saved" to GpsReplay.saved(raw, type))

    fun assertDistance(label: String, expected: Double, actual: Double, tolerancePercent: Double) {
        val errorPercent = abs(actual - expected) / expected * 100
        assertTrue(
            "$label: expected %.0f m ± %.1f%%, got %.0f m (%.2f%%)"
                .format(expected, tolerancePercent, actual, errorPercent),
            errorPercent <= tolerancePercent
        )
    }

    fun assertNoTeleport(label: String, run: SyntheticRun, result: ReplayResult) {
        val worst = result.points.maxOf { run.offRouteMeters(it) }
        assertTrue("$label: accepted point %.0f m off route".format(worst), worst < MAX_OFF_ROUTE_M)
    }
}
