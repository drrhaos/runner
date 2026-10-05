package com.runner.academy.util

import com.runner.academy.data.LocationSource
import com.runner.academy.data.TrackPoint
import com.runner.academy.util.TrackRun.Bridge
import com.runner.academy.util.TrackRun.Gap
import com.runner.academy.util.TrackRun.Solid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sqrt

class RoutePreviewTest {

    private fun point(
        lat: Double,
        lon: Double,
        afterGap: Boolean = false,
        bridgeMeters: Float? = null,
        source: LocationSource = LocationSource.GPS
    ) = TrackPoint(
        latitude = lat,
        longitude = lon,
        timestamp = 0L,
        accuracy = 5f,
        speed = 3f,
        altitude = null,
        afterGap = afterGap,
        source = source.name,
        bridgeMeters = bridgeMeters
    )

    /** A wiggly line of [count] points starting at [lat0], [lon0], every coordinate off the 1e-6 grid. */
    private fun line(count: Int, lat0: Double = 55.7512345678, lon0: Double = 37.6187654321): List<TrackPoint> =
        List(count) { i ->
            point(lat0 + i * 0.0000913377 + (i % 3) * 0.0000017, lon0 + i * 0.0001370123 - (i % 5) * 0.0000023)
        }

    /** Solid, gap, solid, bridge, solid, step bridge, solid. */
    private fun brokenTrack(): List<TrackPoint> {
        val a = line(30)
        val b = line(30, lat0 = 55.76, lon0 = 37.63)
        val c = line(30, lat0 = 55.77, lon0 = 37.64)
        val d = line(30, lat0 = 55.78, lon0 = 37.65)
        return a +
            b.first().copy(afterGap = true) + b.drop(1) +
            c.first().copy(afterGap = true, bridgeMeters = 120f) + c.drop(1) +
            d.first().copy(afterGap = true, bridgeMeters = 300f, source = LocationSource.PEDOMETER.name) + d.drop(1)
    }

    private fun kinds(runs: List<TrackRun>): List<String> = runs.map {
        when (it) {
            is Solid -> "S"
            is Gap -> "G"
            is Bridge -> if (it.fromPedometer) "P" else "B"
        }
    }

    private fun maxDeviationMeters(expected: List<TrackRun>, actual: List<TrackRun>): Double {
        assertEquals(kinds(expected), kinds(actual))
        var worst = 0.0
        expected.zip(actual).forEach { (e, a) ->
            assertEquals(e.points.size, a.points.size)
            e.points.zip(a.points).forEach { (p, q) -> worst = max(worst, meters(p, q)) }
        }
        return worst
    }

    /** Equirectangular distance: exact enough for centimetres, no android.location in a JVM test. */
    private fun meters(p: TrackPoint, q: TrackPoint): Double {
        val metersPerDegree = 111_320.0
        val dLat = (p.latitude - q.latitude) * metersPerDegree
        val dLon = (p.longitude - q.longitude) * metersPerDegree * cos(Math.toRadians(p.latitude))
        return sqrt(dLat * dLat + dLon * dLon)
    }

    @Test
    fun roundTrip_keepsGapsBridgesAndStepBridges() {
        val points = brokenTrack()
        val preview = RoutePreviews.of(points)!!

        val decoded = RoutePreviewCodec.decode(RoutePreviewCodec.encode(preview))!!

        assertEquals(points.size, decoded.pointCount)
        assertEquals(listOf("S", "G", "S", "B", "S", "P", "S"), kinds(decoded.runs))
        // The decoded points are what TrackRuns itself reads as gaps and bridges
        assertEquals(kinds(decoded.runs), kinds(TrackRuns.split(decoded.runs.allPoints())))
        assertTrue(maxDeviationMeters(preview.runs, decoded.runs) < 0.2)
    }

    @Test
    fun roundTrip_keepsTheEndsOfEverySolid() {
        val points = brokenTrack()
        val preview = RoutePreviews.of(points)!!
        val decoded = RoutePreviewCodec.decode(RoutePreviewCodec.encode(preview))!!

        val sourceEnds = TrackRuns.split(points).solids().flatMap { listOf(it.points.first(), it.points.last()) }
        val decodedEnds = decoded.runs.solids().flatMap { listOf(it.points.first(), it.points.last()) }
        assertEquals(sourceEnds.size, decodedEnds.size)
        sourceEnds.zip(decodedEnds).forEach { (p, q) ->
            assertEquals(p.latitude, q.latitude, 0.5e-6)
            assertEquals(p.longitude, q.longitude, 0.5e-6)
        }
    }

    @Test
    fun longTrack_deviatesLessThanTwentyCentimetres() {
        val preview = RoutePreviews.of(line(20_000))!!

        val decoded = RoutePreviewCodec.decode(RoutePreviewCodec.encode(preview))!!

        assertTrue(maxDeviationMeters(preview.runs, decoded.runs) < 0.2)
    }

    @Test
    fun shortTrack_keepsBothPoints() {
        val points = line(2)

        val preview = RoutePreviews.of(points)!!

        assertEquals(2, preview.pointCount)
        assertEquals(2, preview.runs.allPoints().size)
    }

    @Test
    fun longTrack_isThinnedToTheDrawLimitPlusEnds() {
        val points = line(20_000)
        val broken = points.mapIndexed { i, p -> if (i % 5_000 == 0 && i > 0) p.copy(afterGap = true) else p }

        val plain = RoutePreviews.of(points)!!
        val withGaps = RoutePreviews.of(broken)!!

        assertEquals(20_000, plain.pointCount)
        assertTrue(plain.runs.allPoints().size <= RoutePreviews.MAX_POINTS)
        val solids = withGaps.runs.solids().size
        assertEquals(4, solids)
        assertTrue(withGaps.runs.allPoints().size <= RoutePreviews.MAX_POINTS + 2 * solids)
    }

    @Test
    fun fewerThanTwoPoints_haveNoPreview() {
        assertNull(RoutePreviews.of(emptyList()))
        assertNull(RoutePreviews.of(line(1)))
    }

    @Test
    fun longTrack_encodesToAFewKilobytes() {
        val text = RoutePreviewCodec.encode(RoutePreviews.of(line(20_000))!!)

        assertTrue(text.startsWith("1|20000|S:"))
        assertTrue("${text.length} chars", text.length < 4_000)
    }

    @Test
    fun garbage_decodesToNull() {
        val valid = RoutePreviewCodec.encode(RoutePreviews.of(brokenTrack())!!)
        val garbage = listOf(
            null,
            "",
            "   ",
            "garbage",
            "1",
            "1|",
            "1|5",
            "1|5|",
            "2|5|S:1,1;2,2",
            "1|x|S:1,1;2,2",
            "1|-1|S:1,1;2,2",
            "1|5|S:1,1",
            "1|5|S:1,1;2",
            "1|5|S:1,1;a,b",
            "1|5|S:1,1;2,2|G",
            "1|5|G|S:1,1;2,2",
            "1|5|S:1,1|X|S:2,2",
            "1|5|S:1,1|G|G|S:2,2",
            "1|5|S:1,1;;2,2",
            "1|5|T:1,1;2,2",
            "1|5|S:999999999,1;1,1",
            "1|5|S:1,9999999999999999999999;1,1",
            valid.dropLast(3) + "|",
            valid.replace('G', 'Q')
        )
        for (text in garbage) {
            assertNull(text, RoutePreviewCodec.decode(text))
        }
    }

    @Test
    fun decodedPoints_carryTheMarksTrackRunsReads() {
        val decoded = RoutePreviewCodec.decode(RoutePreviewCodec.encode(RoutePreviews.of(brokenTrack())!!))!!

        val gap = decoded.runs.gaps().single()
        assertTrue(gap.to.afterGap)
        assertNull(gap.to.bridgeMeters)
        val (bridge, stepBridge) = decoded.runs.bridges()
        assertTrue(bridge.to.afterGap)
        assertNotNull(bridge.to.bridgeMeters)
        assertFalse(bridge.fromPedometer)
        assertTrue(stepBridge.fromPedometer)
    }
}
