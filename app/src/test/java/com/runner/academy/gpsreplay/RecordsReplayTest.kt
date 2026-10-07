package com.runner.academy.gpsreplay

import com.runner.academy.data.LocationSource
import com.runner.academy.data.RecordDistance
import com.runner.academy.data.TrackPoint
import com.runner.academy.data.Workout
import com.runner.academy.gpsreplay.SyntheticRun.Phase
import com.runner.academy.util.BestEfforts
import com.runner.academy.util.DerivationInput
import com.runner.academy.util.Effort
import com.runner.academy.util.GpxImporter
import com.runner.academy.util.RecordEligibility
import com.runner.academy.util.StepDistanceEstimator
import com.runner.academy.util.TrackDataJson
import com.runner.academy.util.TrackGeometry
import com.runner.academy.util.WorkoutDerivation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * Records on the bench (release acceptance, `product.md`): each scenario runs the whole save
 * path (session → saved track → derivation), and a window's time is expected to the second.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RecordsReplayTest {

    private val street = listOf(0.0 to 0.0, 15_000.0 to 0.0)

    private fun efforts(workout: Workout): List<Effort> =
        WorkoutDerivation.derive(DerivationInput(workout.trackData, workout.type, workout.duration)).efforts

    /** The runner's stride is known: a silence is bridged by steps. */
    private val settings = ReplaySettings(stepDistance = StepDistanceEstimator { steps, _ -> steps * STRIDE_M.toFloat() })

    private fun saved(run: SyntheticRun, events: List<ReplayEvent> = emptyList()): Workout =
        SessionReplay.run(run, settings, events).savedWorkout

    /**
     * Geodesic metres per metre of the synthetic plane along the street: the bench projects with
     * a sphere, [TrackGeometry] measures on the ellipsoid, a few metres per 5 km apart.
     */
    private val scale: Double by lazy {
        val line = SyntheticRun(route = listOf(0.0 to 0.0, 1_000.0 to 0.0), noiseInnovationM = 0.0).rawPoints()
        TrackGeometry.distanceMeters(line.first(), line.last()) / 1_000.0
    }

    /** Time to run [meters] as the track measures them at [speedMps]. */
    private fun runMs(meters: Double, speedMps: Double) = meters / scale / speedMps * 1000

    /** Time of the one break of the track (its distance is not counted). */
    private fun breakMs(workout: Workout): Long {
        val points = points(workout)
        val i = points.indexOfFirst { it.afterGap }
        return points[i].timestamp - points[i - 1].timestamp
    }

    private fun List<Effort>.on(distance: RecordDistance) = find { it.distanceM == distance.meters }

    private fun points(workout: Workout): List<TrackPoint> = TrackDataJson.parse(workout.trackData)!!.points

    private fun describe(workout: Workout): String {
        val points = points(workout)
        return "points=${points.size} gaps=${points.count { it.afterGap }} " +
            "bridges=${points.filter { it.bridgeMeters != null }.map { "${it.source}:${it.bridgeMeters}" }} " +
            "tail=${points.lastOrNull()?.tailMeters} total=${TrackGeometry.totalDistanceMeters(points)}"
    }

    @Test
    fun `a 5 km at 20 min inside 12 km is found to the second`() {
        val run = SyntheticRun(
            route = street,
            phases = listOf(Phase.Run(3_500.0, 3.0), Phase.Run(5_000.0, 5_000.0 / 1_200.0), Phase.Run(3_500.0, 3.0)),
            noiseInnovationM = 0.0
        )

        val efforts = efforts(saved(run))

        val fast = 5_000.0 / 1_200.0
        assertEquals(runMs(5_000.0, fast), efforts.on(RecordDistance.KM_5)!!.elapsedMs.toDouble(), 1_000.0)
        assertEquals(runMs(1_000.0, fast), efforts.on(RecordDistance.KM_1)!!.elapsedMs.toDouble(), 1_000.0)
        // The whole fast stretch, the rest at 3 m/s
        val ten = runMs(5_000.0 * scale, fast) + runMs(10_000.0 - 5_000.0 * scale, 3.0)
        assertEquals(ten, efforts.on(RecordDistance.KM_10)!!.elapsedMs.toDouble(), 1_000.0)
        assertNull(efforts.on(RecordDistance.HALF_MARATHON))
    }

    @Test
    fun `with GPS noise the 5 km is found within a few seconds`() {
        val run = SyntheticRun(
            route = street,
            phases = listOf(Phase.Run(3_500.0, 3.0), Phase.Run(5_000.0, 5_000.0 / 1_200.0), Phase.Run(3_500.0, 3.0))
        )

        val five = efforts(saved(run)).on(RecordDistance.KM_5)!!

        val exact = runMs(5_000.0, 5_000.0 / 1_200.0)
        println("RecordsReplay noisy 5 km: ${five.elapsedMs} ms, exact $exact ms")
        // The noise lengthens the line a little: the window is a few seconds short, never long
        assertTrue("${five.elapsedMs}", five.elapsedMs.toDouble() in exact - 10_000.0..exact + 1_000.0)
    }

    @Test
    fun `a window through a manual pause counts the pause`() {
        val run = SyntheticRun(
            route = street,
            phases = listOf(Phase.Run(2_550.0, 4.0), Phase.ManualPause(60), Phase.Run(2_550.0, 4.0)),
            noiseInnovationM = 0.0
        )

        val workout = saved(run)
        println("RecordsReplay pause: ${describe(workout)}")
        val efforts = efforts(workout)

        assertEquals(runMs(1_000.0, 4.0), efforts.on(RecordDistance.KM_1)!!.elapsedMs.toDouble(), 1_000.0)
        // The pause (between the fixes around it: 60 s and a second or two) is in the 5 km
        assertTrue("${breakMs(workout)}", breakMs(workout) in 60_000L..62_000L)
        assertEquals(runMs(5_000.0, 4.0) + breakMs(workout), efforts.on(RecordDistance.KM_5)!!.elapsedMs.toDouble(), 1_000.0)
    }

    @Test
    fun `a gap without steps counts its time but no distance`() {
        // 250 s (1 km) without fixes in the middle of 6.1 km at 4 m/s
        val run = SyntheticRun(route = street.take(1) + (6_100.0 to 0.0), speedMps = 4.0, gapSec = 500..749, noiseInnovationM = 0.0)

        val workout = SessionReplay.run(run, ReplaySettings()).savedWorkout
        println("RecordsReplay gap: ${describe(workout)}")
        val five = efforts(workout).on(RecordDistance.KM_5)!!

        // Every 5 km spans the gap: 5 km run + its 251 s (the gap counted would make it ~1 250 s)
        assertEquals(251_000L, breakMs(workout))
        assertEquals(runMs(5_000.0, 4.0) + breakMs(workout), five.elapsedMs.toDouble(), 1_000.0)
    }

    /**
     * [beforeM] east at 3.3 m/s with a 1.1 m stride, a U-turn of [detourM] (out north and back)
     * without fixes, then [afterM] east: over the silence the straight line is 1 m and only the
     * steps tell the distance. [walkMps]: the detour is walked (a silence must be long to be
     * bridged). A little GPS noise, as a real run has.
     */
    private fun detour(beforeM: Double, detourM: Double, afterM: Double, walkMps: Double? = null): SyntheticRun {
        val out = (detourM - 1.0) / 2
        val turnM = beforeM + 1.0
        val detourSec = detourM / (walkMps ?: 3.3)
        return SyntheticRun(
            route = listOf(0.0 to 0.0, beforeM to 0.0, beforeM to out, turnM to out, turnM to 0.0, turnM + afterM to 0.0),
            speedMps = 3.3,
            strideM = STRIDE_M,
            gapSec = (beforeM / 3.3).toInt() + 1..(beforeM / 3.3 + detourSec).toInt(),
            noiseInnovationM = 0.1,
            phases = walkMps?.let {
                listOf(Phase.Run(beforeM, 3.3), Phase.Walk(detourSec.toInt(), it), Phase.Run(afterM + 1.0, 3.3))
            }
        )
    }

    /** The bridge by steps of the track. */
    private fun bridge(workout: Workout): TrackPoint =
        points(workout).single { it.bridgeMeters != null && it.source == LocationSource.PEDOMETER.name }

    @Test
    fun `a bridge by steps of 5 percent counts, marked approximate`() {
        // 1.2 km: every 1 km window holds the whole 50 m bridge (a minute walked)
        val workout = saved(detour(beforeM = 575.0, detourM = 51.0, afterM = 575.0, walkMps = 0.85))
        println("RecordsReplay bridge 5 %: ${describe(workout)}")
        val points = points(workout)
        val bridge = bridge(workout)
        val bridgeMs = bridge.timestamp - points[points.indexOf(bridge) - 1].timestamp

        val one = efforts(workout).on(RecordDistance.KM_1)!!

        assertEquals(bridgeMs + runMs(1_000.0 - bridge.bridgeMeters!!, 3.3), one.elapsedMs.toDouble(), 1_000.0)
        assertEquals(bridge.bridgeMeters!! / 1_000f, one.stepsShare, 0.001f)
        assertEquals(0.05f, one.stepsShare, 0.01f)
    }

    @Test
    fun `a bridge by steps of 30 percent is never taken whole`() {
        // Steps overcount the 300 m detour (1.3 m a step for 1.1): over it the run looks fast
        val run = detour(beforeM = 1_100.0, detourM = 300.0, afterM = 1_100.0)
        val overcounting = settings.copy(stepDistance = StepDistanceEstimator { steps, _ -> steps * 1.3f })
        val workout = SessionReplay.run(run, overcounting).savedWorkout
        println("RecordsReplay bridge 30 %: ${describe(workout)}")
        val bridgeM = bridge(workout).bridgeMeters!!.toDouble()
        assertEquals(300.0 * 1.3 / STRIDE_M, bridgeM, 10.0)

        val one = efforts(workout).on(RecordDistance.KM_1)!!

        assertTrue("share ${one.stepsShare}", one.stepsShare <= BestEfforts.MAX_STEPS_SHARE)
        // Whole, the bridge would give 1 km in about 290 s; at most 100 m of it is in the window
        val wholeBridge = 300_000.0 / 3.3 + runMs(1_000.0 - bridgeM, 3.3)
        val tenthOfIt = 100_000.0 / 3.3 * STRIDE_M / 1.3 + runMs(900.0, 3.3)
        assertTrue("${one.elapsedMs} vs $wholeBridge", one.elapsedMs > wholeBridge + 10_000.0)
        assertEquals(tenthOfIt, one.elapsedMs.toDouble(), 1_000.0)
    }

    @Test
    fun `the tail after the last fix is no part of a window`() {
        // Stopped during a silence: the last 200 m are counted from steps after the last fix
        val run = SyntheticRun(
            route = listOf(0.0 to 0.0, 1_100.0 to 0.0),
            speedMps = 3.3,
            strideM = STRIDE_M,
            gapSec = (900 / 3.3).toInt() + 1..(1_100 / 3.3).toInt() + 1,
            noiseInnovationM = 0.1
        )
        val workout = saved(run, listOf(ReplayEvent.Stop(atSec = run.durationSec)))
        println("RecordsReplay tail: ${describe(workout)}")
        val track = TrackDataJson.parse(workout.trackData)!!

        assertTrue("tail ${track.points.last().tailMeters}", (track.points.last().tailMeters ?: 0f) > 150f)
        assertTrue(TrackGeometry.totalDistanceMeters(track.points) > 1_000f)
        assertTrue("a track that may hold records", RecordEligibility.isTrusted(track))
        assertNull(efforts(workout).on(RecordDistance.KM_1))
    }

    /** A GPX of [points]; with [withTime] false only the first point keeps its time. */
    private fun gpx(points: List<TrackPoint>, withTime: Boolean = true): String = buildString {
        append("<gpx version=\"1.1\"><trk><trkseg>\n")
        points.forEachIndexed { i, p ->
            val time = if (withTime || i == 0) "<time>${Instant.ofEpochMilli(p.timestamp)}</time>" else ""
            append("<trkpt lat=\"${p.latitude}\" lon=\"${p.longitude}\">$time</trkpt>\n")
        }
        append("</trkseg></trk></gpx>")
    }

    private val fileRun = SyntheticRun(route = listOf(0.0 to 0.0, 6_000.0 to 0.0), speedMps = 3.3)

    @Test
    fun `a GPX without time sets no record, with time it does`() {
        val raw = fileRun.rawPoints()

        assertTrue(efforts(GpxImporter.parseGpx(gpx(raw, withTime = false))).isEmpty())
        assertNotNull(efforts(GpxImporter.parseGpx(gpx(raw))).on(RecordDistance.KM_5))
    }

    @Test
    fun `a jump faster than the world record drops the run on that distance`() {
        val raw = fileRun.rawPoints().toMutableList()
        // One false fix 450 m off the line (under the cleaner's 500 m): 900 m in two seconds
        raw[900] = raw[900].copy(latitude = raw[900].latitude + 450.0 / 111_320.0)

        val efforts = efforts(GpxImporter.parseGpx(gpx(raw)))

        assertNull("no 1 km at all, not the next best window", efforts.on(RecordDistance.KM_1))
        assertNotNull("5 km over the jump is still slower than the world record", efforts.on(RecordDistance.KM_5))
    }

    @Test
    fun `a bridge by GPS line is not counted as steps`() {
        // The source mark alone decides: a straight-line bridge is part of the run
        val workout = saved(detour(beforeM = 575.0, detourM = 51.0, afterM = 575.0, walkMps = 0.85))
        assertNotNull(bridge(workout))
        val points = points(workout).map { if (it.bridgeMeters != null) it.copy(source = LocationSource.GPS.name) else it }
        val json = TrackDataJson.toJson(TrackDataJson.parse(workout.trackData)!!.copy(points = points))

        val one = WorkoutDerivation.derive(DerivationInput(json, workout.type, workout.duration)).efforts.on(RecordDistance.KM_1)

        assertEquals(0f, one?.stepsShare ?: 0f, 0f)
    }

    private companion object {
        const val STRIDE_M = 1.1
    }
}
