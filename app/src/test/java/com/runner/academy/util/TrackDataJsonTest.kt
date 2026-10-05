package com.runner.academy.util

import com.runner.academy.data.ElevationSource
import com.runner.academy.data.LocationSource
import com.runner.academy.data.PauseInterval
import com.runner.academy.data.TrackData
import com.runner.academy.data.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TrackDataJsonTest {

    private fun track(points: List<TrackPoint>) = TrackData(points, 1f, 1L, 1f, 1f, 0L, 1L)

    @Test
    fun `release 2 fields survive a round trip`() {
        val points = listOf(
            TrackPoint(55.0, 37.0, 1L, 5f, 3f, 150.0, bridgeMeters = 120f, steps = 130, cadence = 172f),
            TrackPoint(
                55.01, 37.0, 2L, 5f, 3f, 150.0,
                afterGap = true,
                source = LocationSource.PEDOMETER.name,
                bridgeMeters = 980f,
                steps = 1_000,
                cadence = 170.5f,
                tailMeters = 240f
            )
        )

        val parsed = TrackDataJson.parse(TrackDataJson.toJson(track(points)))!!

        assertEquals(points, parsed.points)
    }

    @Test
    fun `old tracks without the optional fields read as null`() {
        val json = """{"points":[{"latitude":55.0,"longitude":37.0,"timestamp":1}],
            "total_distance":0,"total_duration":0,"avg_speed":0,"max_speed":0,"start_time":0}"""

        val point = TrackDataJson.parse(json)!!.points.single()

        assertNull(point.bridgeMeters)
        assertNull(point.steps)
        assertNull(point.cadence)
    }

    @Test
    fun `release 3 track fields survive a round trip`() {
        val pauses = listOf(PauseInterval(10L, 70L, "AUTO"), PauseInterval(100L, 160L, "MANUAL"))
        val track = track(listOf(TrackPoint(55.0, 37.0, 1L, 5f, 3f, 150.0))).copy(
            pauses = pauses,
            timeSynthetic = true,
            elevationSource = ElevationSource.FILE
        )

        val json = TrackDataJson.toJson(track)
        val parsed = TrackDataJson.parse(json)!!

        assertTrue(json.contains("\"pauses\"") && json.contains("\"time_synthetic\"") && json.contains("\"elevation_source\""))
        assertEquals(track, parsed)
    }

    @Test
    fun `old tracks without the release 3 fields read as null`() {
        val json = """{"points":[{"latitude":55.0,"longitude":37.0,"timestamp":1}],
            "total_distance":0,"total_duration":0,"avg_speed":0,"max_speed":0,"start_time":0}"""

        val parsed = TrackDataJson.parse(json)!!

        assertNull(parsed.pauses)
        assertNull(parsed.timeSynthetic)
        assertNull(parsed.elevationSource)
        assertFalse(TrackDataJson.toJson(parsed).contains("pauses"))
    }

    @Test
    fun `a pause without a kind reads as auto`() {
        val json = """{"points":[],"total_distance":0,"total_duration":0,"avg_speed":0,"max_speed":0,
            "start_time":0,"pauses":[{"start":5,"end":9}]}"""

        assertEquals(listOf(PauseInterval(5L, 9L, "AUTO")), TrackDataJson.parse(json)!!.pauses)
    }
}
