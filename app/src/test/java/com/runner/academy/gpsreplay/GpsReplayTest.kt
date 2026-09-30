package com.runner.academy.gpsreplay

import com.google.gson.JsonParser
import com.runner.academy.data.WorkoutType
import com.runner.academy.util.TrackDataJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.math.abs

/**
 * GPS track-quality regression tests: synthetic runs with known true distance are replayed
 * through the live and save pipelines. A filter change that drops points, splits the track
 * or lets outliers through fails here instead of on a real run.
 *
 * Real tracks can be added as fixtures, see `src/test/resources/gps-replay/README.md`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class GpsReplayTest {

    private fun assertDistance(label: String, expected: Double, actual: Double, tolerancePercent: Double) {
        val errorPercent = abs(actual - expected) / expected * 100
        assertTrue(
            "$label: expected %.0f m ± %.1f%%, got %.0f m (%.2f%%)"
                .format(expected, tolerancePercent, actual, errorPercent),
            errorPercent <= tolerancePercent
        )
    }

    private fun bothPipelines(run: SyntheticRun, type: WorkoutType = WorkoutType.EASY_RUN): List<Pair<String, ReplayResult>> {
        val raw = run.rawPoints()
        return listOf("live" to GpsReplay.live(raw, type), "saved" to GpsReplay.saved(raw, type))
    }

    private fun assertRetainsFixes(label: String, rawCount: Int, result: ReplayResult) {
        val share = result.points.size.toDouble() / rawCount
        assertTrue(
            "$label: kept %d of %d fixes (%.0f%%)".format(result.points.size, rawCount, share * 100),
            share >= MIN_RETAINED_SHARE
        )
    }

    @Test
    fun `clean run at 1 Hz keeps distance, points and has no gaps`() {
        val run = SyntheticRun(SyntheticRun.blockLoop())
        val rawCount = run.rawPoints().size
        for ((name, result) in bothPipelines(run)) {
            assertDistance(name, run.routeLengthM, result.distanceMeters, DISTANCE_TOLERANCE_PERCENT)
            assertEquals("$name gaps", 0, result.gapCount)
            assertRetainsFixes(name, rawCount, result)
        }
    }

    @Test
    fun `screen-off cadence of 2 s keeps distance, points and has no gaps`() {
        val run = SyntheticRun(SyntheticRun.blockLoop(), intervalMs = 2_000L)
        val rawCount = run.rawPoints().size
        for ((name, result) in bothPipelines(run)) {
            assertDistance(name, run.routeLengthM, result.distanceMeters, DISTANCE_TOLERANCE_PERCENT)
            assertEquals("$name gaps", 0, result.gapCount)
            assertRetainsFixes(name, rawCount, result)
        }
    }

    @Test
    fun `tunnel produces exactly one gap without phantom distance`() {
        val run = SyntheticRun(SyntheticRun.blockLoop(), gapSec = 200..240)
        val expected = run.routeLengthM - run.gapDistanceM
        for ((name, result) in bothPipelines(run)) {
            assertEquals("$name gaps", 1, result.gapCount)
            assertDistance(name, expected, result.distanceMeters, DISTANCE_TOLERANCE_PERCENT)
        }
    }

    @Test
    fun `single-fix outliers are rejected`() {
        val run = SyntheticRun(SyntheticRun.blockLoop(), outlierAtSec = setOf(60, 180, 300, 420, 540))
        for ((name, result) in bothPipelines(run)) {
            assertDistance(name, run.routeLengthM, result.distanceMeters, DISTANCE_TOLERANCE_PERCENT)
            val worst = result.points.maxOf { run.offRouteMeters(it) }
            assertTrue("$name: accepted point %.0f m off route".format(worst), worst < MAX_OFF_ROUTE_M)
        }
    }

    @Test
    fun `standing still before the run adds almost no distance`() {
        val run = SyntheticRun(SyntheticRun.blockLoop(), standStillSec = 60)
        for ((name, result) in bothPipelines(run)) {
            assertDistance(name, run.routeLengthM, result.distanceMeters, DISTANCE_TOLERANCE_PERCENT)
        }
    }

    @Test
    fun `live and saved pipelines agree`() {
        val run = SyntheticRun(SyntheticRun.blockLoop(laps = 3), gapSec = 300..330, outlierAtSec = setOf(100, 500))
        val raw = run.rawPoints()
        val live = GpsReplay.live(raw)
        val saved = GpsReplay.saved(raw)
        assertDistance("saved vs live", live.distanceMeters, saved.distanceMeters, PIPELINE_AGREEMENT_PERCENT)
        assertEquals("gap count", live.gapCount, saved.gapCount)
    }

    /** Real tracks exported from the app; each file states its own expectations. */
    @Test
    fun `recorded fixtures stay within their expectations`() {
        val dir = fixturesDir() ?: return
        val files = dir.listFiles { f -> f.extension == "json" }.orEmpty().sortedBy { it.name }
        for (file in files) {
            val json = JsonParser.parseString(file.readText()).asJsonObject
            val track = TrackDataJson.parse(json.get("track").toString())
            assertNotNull("${file.name}: unreadable track", track)
            val type = json.get("workoutType")?.asString?.let { WorkoutType.valueOf(it) } ?: WorkoutType.EASY_RUN
            val expected = json.get("expectedDistanceMeters").asDouble
            val tolerance = json.get("tolerancePercent")?.asDouble ?: DISTANCE_TOLERANCE_PERCENT
            val maxGaps = json.get("maxGaps")?.asInt ?: Int.MAX_VALUE
            for ((name, result) in listOf(
                "live" to GpsReplay.live(track!!.points, type),
                "saved" to GpsReplay.saved(track.points, type)
            )) {
                assertDistance("${file.name} $name", expected, result.distanceMeters, tolerance)
                assertTrue("${file.name} $name: ${result.gapCount} gaps > $maxGaps", result.gapCount <= maxGaps)
            }
        }
    }

    private fun fixturesDir(): File? =
        javaClass.classLoader?.getResource("gps-replay")?.let { File(it.toURI()) }?.takeIf { it.isDirectory }

    private companion object {
        /** Synthetic runs land within ~1%; 2% leaves room for noise, not for regressions. */
        const val DISTANCE_TOLERANCE_PERCENT = 2.0
        const val MIN_RETAINED_SHARE = 0.9
        const val PIPELINE_AGREEMENT_PERCENT = 1.0
        const val MAX_OFF_ROUTE_M = 30.0
    }
}
