package com.runner.academy.util

import com.runner.academy.data.TrackData
import com.runner.academy.data.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Date
import kotlin.math.cos

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RouteTimeAlignerTest {

    private val lat0 = 55.75
    private val lon0 = 37.6
    private val start = 1_700_000_000_000L

    /** Point [eastM] metres east and [northM] metres north of the origin. */
    private fun point(eastM: Double, northM: Double = 0.0, time: Long = 0L) = TrackPoint(
        latitude = lat0 + northM / 111_320.0,
        longitude = lon0 + eastM / (111_320.0 * cos(Math.toRadians(lat0))),
        timestamp = time,
        accuracy = 5f,
        speed = null,
        altitude = null
    )

    private fun track(points: List<TrackPoint>) = RouteTimeAligner.buildTrackData(points)

    /** 1 km east at 3 s / 10 m, then back at 5 s / 10 m. */
    private fun recordedOutAndBack(): TrackData {
        val out = (0..100).map { point(it * 10.0, time = start + it * 3_000L) }
        val outEnd = start + 100 * 3_000L
        val back = (1..100).map { point(1_000.0 - it * 10.0, time = outEnd + it * 5_000L) }
        return track(out + back)
    }

    @Test
    fun `alignToRecorded takes time from matching pass on out-and-back route`() {
        // Same road, 5 m to the side, sparse points every 50 m.
        val routePoints = (0..20).map { point(it * 50.0, 5.0) } + (1..20).map { point(1_000.0 - it * 50.0, 5.0) }
        val aligned = RouteTimeAligner.alignToRecorded(track(routePoints), recordedOutAndBack())
        assertNotNull(aligned)
        val points = aligned!!.points

        // x = 500 m outbound → 150 s; x = 500 m on return → 300 s + 250 s.
        assertEquals(start + 150_000L, points[10].timestamp.toDouble(), 2_000.0)
        assertEquals(start + 550_000L, points[30].timestamp.toDouble(), 2_000.0)
        assertEquals(800_000L, aligned.totalDuration.toDouble(), 3_000.0)
        for (i in 1 until points.size) {
            assertTrue("timestamps must increase", points[i].timestamp >= points[i - 1].timestamp)
        }
        // Speed is derived from the new timing: ~3.33 m/s out, ~2 m/s back.
        assertEquals(3.33f, points[10].speed!!, 0.2f)
        assertEquals(2.0f, points[30].speed!!, 0.2f)
    }

    @Test
    fun `alignToRecorded interpolates route section that deviates from recording`() {
        val recorded = track((0..100).map { point(it * 10.0, time = start + it * 3_000L) })
        // Detour 200 m north in the middle (points 4..6), rest on the recorded line.
        val routePoints = listOf(
            point(0.0), point(200.0), point(400.0),
            point(450.0, 200.0), point(500.0, 200.0), point(550.0, 200.0),
            point(600.0), point(800.0), point(1_000.0)
        )
        val aligned = RouteTimeAligner.alignToRecorded(track(routePoints), recorded)!!
        val t = aligned.points.map { it.timestamp }
        assertEquals(start + 120_000L, t[2].toDouble(), 1_000.0)
        assertEquals(start + 180_000L, t[6].toDouble(), 1_000.0)
        assertTrue(t[3] in t[2]..t[4] && t[4] in t[3]..t[5] && t[5] in t[4]..t[6])
    }

    @Test
    fun `alignToRecorded returns null when route is elsewhere`() {
        val recorded = track((0..100).map { point(it * 10.0, time = start + it * 3_000L) })
        val route = track((0..20).map { point(it * 50.0, 1_000.0) })
        assertNull(RouteTimeAligner.alignToRecorded(route, recorded))
    }

    @Test
    fun `alignToRecorded extrapolates route beyond recording with average speed`() {
        val recorded = track((0..100).map { point(it * 10.0, time = start + it * 3_000L) })
        val route = track((0..12).map { point(it * 100.0) }) // 1.2 km, last 200 m unrecorded
        val aligned = RouteTimeAligner.alignToRecorded(route, recorded)!!
        assertEquals(start + 360_000L, aligned.points.last().timestamp.toDouble(), 3_000.0)
    }

    @Test
    fun `distributeByDistance spreads duration proportionally`() {
        val route = track(listOf(point(0.0), point(100.0), point(400.0)))
        val result = RouteTimeAligner.distributeByDistance(route, start, 400_000L)!!
        assertEquals(start + 100_000L, result.points[1].timestamp.toDouble(), 2_000.0)
        assertEquals(400_000L, result.totalDuration)
    }

    @Test
    fun `rebuilder uses recorded track when route is replaced`() {
        val recordedJson = TrackDataJson.toJson(recordedOutAndBack())
        val routeJson = TrackDataJson.toJson(
            track((0..20).map { point(it * 50.0, 5.0, time = 42L + it) })
        )
        val result = WorkoutTrackRebuilder.rebuild(recordedJson, routeJson, Date(start), Date(start), 1L)
        assertEquals(WorkoutTrackRebuilder.TimeSource.RECORDED, result.timeSource)
        val rebuilt = TrackDataJson.parse(result.trackDataJson)!!
        assertEquals(start.toDouble(), rebuilt.startTime.toDouble(), 2_000.0)
    }

    @Test
    fun `rebuilder distributes evenly for workout without recording`() {
        val routeJson = TrackDataJson.toJson(track((0..10).map { point(it * 100.0, time = 42L + it) }))
        val result = WorkoutTrackRebuilder.rebuild(null, routeJson, null, Date(start), 600_000L)
        assertEquals(WorkoutTrackRebuilder.TimeSource.UNIFORM, result.timeSource)
        val rebuilt = TrackDataJson.parse(result.trackDataJson)!!
        assertEquals(start, rebuilt.startTime)
        assertEquals(600_000L, rebuilt.totalDuration)
    }

    @Test
    fun `rebuilder shifts unchanged track when date changes`() {
        val json = TrackDataJson.toJson(recordedOutAndBack())
        val day = 86_400_000L
        val result = WorkoutTrackRebuilder.rebuild(json, json, Date(start), Date(start + day), 1L)
        assertEquals(WorkoutTrackRebuilder.TimeSource.SHIFTED, result.timeSource)
        assertEquals(start + day, TrackDataJson.parse(result.trackDataJson)!!.startTime)
    }
}
