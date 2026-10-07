package com.runner.academy.gpsreplay

import com.runner.academy.data.RecordDistance
import com.runner.academy.data.TrackPoint
import com.runner.academy.data.WorkoutType
import com.runner.academy.util.BestEfforts
import com.runner.academy.util.GpsDiagnostics
import com.runner.academy.util.RouteTimeAligner
import com.runner.academy.util.TrackSanitizer
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Local only: the real diagnostics of run 79 (Samsung S22, false fixes ~9 km away mid-run, see
 * [ElevationRealTrackTest]). The file holds exact coordinates, so it never goes into the
 * repository; without it the test is skipped.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RecordsRealTrackTest {

    private val file = File("/media/f/developer/android/runner/runner_gps_diagnostics_79.jsonl")

    private fun rawPoints(): List<TrackPoint> =
        GpsDiagnostics.parse(file.readLines())
            .filterIsInstance<GpsDiagnostics.DiagRecord.Fix>()
            .map { it.fix }
            .map { fix ->
                TrackPoint(
                    fix.latitude, fix.longitude, fix.timeMs, fix.accuracy, fix.speed, fix.altitude,
                    steps = fix.steps, cadence = fix.cadence
                )
            }

    @Test
    fun `the false fixes of run 79 give no faster window`() {
        assumeTrue("local diagnostics file", file.exists())
        val raw = rawPoints()
        assumeTrue(raw.isNotEmpty())
        // The false fixes carry the far burst's altitude
        val isFalse = { p: TrackPoint -> (p.altitude ?: 0.0) > FALSE_ABOVE_M }
        assertTrue("the file has the false fixes", raw.any(isFalse))

        val saved = BestEfforts.compute(RouteTimeAligner.buildTrackData(TrackSanitizer.sanitize(raw, WorkoutType.EASY_RUN)))
        val truth = BestEfforts.compute(
            RouteTimeAligner.buildTrackData(TrackSanitizer.sanitize(raw.filterNot(isFalse), WorkoutType.EASY_RUN))
        )
        println("run 79 efforts: saved=${saved.map { it.distanceM to it.elapsedMs }} truth=${truth.map { it.distanceM to it.elapsedMs }}")

        for (effort in saved) {
            val distance = RecordDistance.ofMeters(effort.distanceM)!!
            assertTrue("$distance slower than the world record", effort.elapsedMs >= distance.worldRecordMs)
            val honest = truth.find { it.distanceM == effort.distanceM } ?: continue
            assertTrue(
                "$distance ${effort.elapsedMs} ms faster than without the false fixes (${honest.elapsedMs} ms)",
                effort.elapsedMs >= honest.elapsedMs - TOLERANCE_MS
            )
        }
    }

    private companion object {
        /** The real altitudes of the run are around 150–250 m. */
        const val FALSE_ABOVE_M = 800.0

        /** The bridges over the dropped stretch may differ by a few metres. */
        const val TOLERANCE_MS = 5_000L
    }
}
