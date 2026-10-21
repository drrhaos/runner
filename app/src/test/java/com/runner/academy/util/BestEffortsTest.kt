package com.runner.academy.util

import com.runner.academy.data.LocationSource
import com.runner.academy.data.RecordDistance
import com.runner.academy.data.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The fastest window per record distance (ticket 06, cases 1–4, and the world-record bound). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BestEffortsTest {

    /** Metres of one degree of latitude here, as [TrackGeometry] measures them. */
    private val metersPerDegree: Double by lazy {
        TrackGeometry.distanceMeters(at(0.0, 0L, 1.0), at(0.0, 0L, 1.0, lat = LAT0 + 0.1)).toDouble() / 0.1
    }

    private fun at(meters: Double, timeMs: Long, perDegree: Double = metersPerDegree, lat: Double? = null) =
        TrackPoint(lat ?: (LAT0 + meters / perDegree), LON0, START + timeMs, 5f, null, null)

    /** A stretch of the run: [meters] at [speedMps]. */
    private data class Leg(val meters: Double, val speedMps: Double) {
        val sec: Double get() = meters / speedMps
    }

    /**
     * One fix every [everySec] seconds (and one at the very end) along a straight line north,
     * moving through [legs] in turn.
     */
    private fun run(vararg legs: Leg, everySec: Double = 1.0): List<TrackPoint> {
        val total = legs.sumOf { it.sec }
        fun metersAt(t: Double): Double {
            var left = t
            var meters = 0.0
            for (leg in legs) {
                if (left <= leg.sec) return meters + leg.meters * (left / leg.sec)
                left -= leg.sec
                meters += leg.meters
            }
            return meters
        }
        val times = generateSequence(0.0) { it + everySec }.takeWhile { it < total }.toList() + total
        return times.map { t -> at(metersAt(t), (t * 1000).toLong()) }
    }

    private fun effort(points: List<TrackPoint>, distance: RecordDistance): Effort? =
        BestEfforts.compute(points, listOf(distance)).singleOrNull()

    @Test
    fun `1 a 5 km inside 12 km is found to the second, its ends inside a step`() {
        // Fixes every 7 s: the fast stretch starts and ends between two fixes
        val points = run(Leg(3_500.0, 3.0), Leg(5_000.0, 5_000.0 / 1_200.0), Leg(3_500.0, 3.0), everySec = 7.0)

        val five = effort(points, RecordDistance.KM_5)!!

        assertEquals(1_200_000.0, five.elapsedMs.toDouble(), 1_000.0)
        assertEquals(5_000, five.distanceM)
        assertEquals(0f, five.stepsShare, 0f)
        // The window lies over the fast stretch (anywhere within it: the metres of the line and
        // of the geodesic differ by a few)
        assertEquals((START + 3_500_000L / 3).toDouble(), five.startTime.toDouble(), 2_000.0)
        assertEquals(five.startTime + five.elapsedMs, five.endTime)
    }

    @Test
    fun `every distance the run covers gets one effort`() {
        val points = run(Leg(12_000.0, 3.0))

        val efforts = BestEfforts.compute(points, RecordDistance.entries)

        assertEquals(listOf(1_000, 5_000, 10_000), efforts.map { it.distanceM })
        assertEquals(1_000_000.0 / 3, efforts[0].elapsedMs.toDouble(), 1_000.0)
        assertEquals(10_000_000.0 / 3, efforts[2].elapsedMs.toDouble(), 1_000.0)
    }

    /**
     * [first], then no fixes for [silentSec] while [silentM] metres pass, then [second] from
     * there; the first fix after the silence opens a break, changed by [resume].
     */
    private fun joined(
        first: List<TrackPoint>,
        silentM: Double,
        silentSec: Double,
        second: List<TrackPoint>,
        resume: (TrackPoint) -> TrackPoint = { it.copy(afterGap = true) }
    ): List<TrackPoint> {
        val offsetM = (first.last().latitude - LAT0) * metersPerDegree + silentM
        val offsetMs = first.last().timestamp - START + (silentSec * 1000).toLong()
        val moved = second.map { at(offsetM + (it.latitude - LAT0) * metersPerDegree, it.timestamp - START + offsetMs) }
        return first + resume(moved.first()) + moved.drop(1)
    }

    @Test
    fun `2 a window through a pause counts the pause, the window beside it is faster`() {
        // A manual pause of 60 s: no fixes, the fix after it opens a break, as the save path writes it
        val points = joined(run(Leg(2_510.0, 4.0)), 0.0, 60.0, run(Leg(2_510.0, 4.0)))

        assertEquals(250_000.0, effort(points, RecordDistance.KM_1)!!.elapsedMs.toDouble(), 1_000.0)
        assertEquals(1_310_000.0, effort(points, RecordDistance.KM_5)!!.elapsedMs.toDouble(), 1_000.0)
    }

    @Test
    fun `2 a gap counts its time but no distance`() {
        // 250 s without fixes while running 1 km, then the track resumes after a break
        val points = joined(run(Leg(3_010.0, 4.0)), 1_000.0, 250.0, run(Leg(2_010.0, 4.0)))

        val five = effort(points, RecordDistance.KM_5)!!

        // 3 km + 2 km counted: every 5 km spans the gap, its time included (1 km more would be 1250 s)
        assertEquals(1_500_000.0, five.elapsedMs.toDouble(), 1_000.0)
    }

    /** [runM] at 3 m/s, a bridge of [bridgeM] by steps in [bridgeSec], [runM] at 3 m/s. */
    private fun bridged(runM: Double, bridgeM: Double, bridgeSec: Double): List<TrackPoint> =
        joined(run(Leg(runM, 3.0)), bridgeM, bridgeSec, run(Leg(runM, 3.0))) {
            it.copy(afterGap = true, bridgeMeters = bridgeM.toFloat(), source = LocationSource.PEDOMETER.name)
        }

    @Test
    fun `3 a bridge by steps of 5 percent counts, marked approximate`() {
        val one = effort(bridged(runM = 600.0, bridgeM = 50.0, bridgeSec = 50.0 / 3), RecordDistance.KM_1)!!

        assertEquals(1_000_000.0 / 3, one.elapsedMs.toDouble(), 1_000.0)
        assertEquals(0.05f, one.stepsShare, 0.001f)
    }

    @Test
    fun `3 a bridge by steps of 30 percent is never taken whole`() {
        // The bridge is fast (5 m/s): a window over it would be 293 s
        val one = effort(bridged(runM = 1_200.0, bridgeM = 300.0, bridgeSec = 60.0), RecordDistance.KM_1)!!

        assertTrue("share ${one.stepsShare}", one.stepsShare <= BestEfforts.MAX_STEPS_SHARE)
        // At most 100 m of it at 5 m/s, the rest run at 3 m/s
        assertEquals(320_000.0, one.elapsedMs.toDouble(), 1_000.0)
    }

    @Test
    fun `3 a bridge by GPS line counts as run, not as steps`() {
        val points = bridged(runM = 600.0, bridgeM = 50.0, bridgeSec = 50.0 / 3)
            .map { if (it.bridgeMeters != null) it.copy(source = LocationSource.GPS.name) else it }

        assertEquals(0f, effort(points, RecordDistance.KM_1)!!.stepsShare)
    }

    @Test
    fun `4 the lead-in and the tail are not part of any window`() {
        val points = run(Leg(900.0, 3.0)).let { list ->
            list.mapIndexed { i, p ->
                when (i) {
                    0 -> p.copy(bridgeMeters = 500f)
                    list.lastIndex -> p.copy(tailMeters = 300f)
                    else -> p
                }
            }
        }

        assertTrue(BestEfforts.compute(points, RecordDistance.entries).isEmpty())
    }

    @Test
    fun `4 a track shorter than 1 km has no efforts`() {
        assertTrue(BestEfforts.compute(run(Leg(999.0, 3.0)), RecordDistance.entries).isEmpty())
        assertTrue(BestEfforts.compute(emptyList(), RecordDistance.entries).isEmpty())
    }

    @Test
    fun `a window faster than the world record drops the workout on that distance`() {
        val points = run(Leg(6_000.0, 3.0)).toMutableList()
        // A false fix 600 m off the line and back within two seconds
        val glitch = 1_000
        points[glitch] = points[glitch].copy(latitude = points[glitch].latitude + 600.0 / metersPerDegree)

        val efforts = BestEfforts.compute(points, RecordDistance.entries)

        // Not the next best 1 km window either: the glitch proves a failure nearby
        assertNull(efforts.find { it.distanceM == 1_000 })
        // 5 km over the glitch is still slower than the world record: kept
        assertTrue(efforts.any { it.distanceM == 5_000 })
    }

    @Test
    fun `an untrusted track has no efforts`() {
        val points = run(Leg(3_000.0, 3.0), Leg(3_000.0, 3.3))
        val track = RouteTimeAligner.buildTrackData(points)

        assertTrue(BestEfforts.compute(track).isNotEmpty())
        assertTrue(BestEfforts.compute(track.copy(timeSynthetic = true)).isEmpty())
    }

    private companion object {
        const val LAT0 = 55.70
        const val LON0 = 37.60
        const val START = 1_700_000_000_000L
    }
}
