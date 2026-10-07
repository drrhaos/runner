package com.runner.academy.gpsreplay

import com.runner.academy.data.ElevationSource
import com.runner.academy.data.TrackPoint
import com.runner.academy.data.WorkoutType
import com.runner.academy.util.ElevationGain
import com.runner.academy.util.GpsDiagnostics
import com.runner.academy.util.TrackSanitizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Local only: the real diagnostics of run 79 (Samsung S22, false fixes at 859/987 and
 * 1189–1314 m mid-run). The file holds exact coordinates, so it never goes into the repository;
 * without it the test is skipped.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ElevationRealTrackTest {

    private val file = File("/media/f/developer/android/runner/runner_gps_diagnostics_79.jsonl")

    /** Every fix of the file as a raw track point, as the service recorded it. */
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
    fun `the false altitudes of run 79 add nothing to the gain`() {
        assumeTrue("local diagnostics file", file.exists())
        val raw = rawPoints()
        assumeTrue(raw.isNotEmpty())
        val falseFixes = raw.count { (it.altitude ?: 0.0) > FALSE_ABOVE_M }
        val withoutFalse = raw.map { if ((it.altitude ?: 0.0) > FALSE_ABOVE_M) it.copy(altitude = null) else it }

        // Straight from the raw fixes: the outlier filter alone must keep them out
        val rawGain = ElevationGain.compute(raw, ElevationSource.GPS)!!
        val cleanGain = ElevationGain.compute(withoutFalse, ElevationSource.GPS)!!
        // And through the save path
        val saved = TrackSanitizer.sanitize(raw, WorkoutType.EASY_RUN)
        val savedGain = ElevationGain.compute(saved, ElevationSource.GPS)!!
        println(
            "run 79: ${raw.size} fixes, $falseFixes false; gain raw=${rawGain.gainM} clean=${cleanGain.gainM} " +
                "saved=${savedGain.gainM}, loss raw=${rawGain.lossM} clean=${cleanGain.lossM} saved=${savedGain.lossM}"
        )

        assertTrue("the file has the false fixes", falseFixes > 0)
        assertEquals(cleanGain.gainM, rawGain.gainM, 5f)
        assertEquals(cleanGain.lossM, rawGain.lossM, 5f)
        assertTrue("saved gain ${savedGain.gainM} far from ${cleanGain.gainM}", savedGain.gainM < cleanGain.gainM + 15f)
    }

    private companion object {
        /** The real altitudes of the run are around 150–250 m. */
        const val FALSE_ABOVE_M = 800.0
    }
}
