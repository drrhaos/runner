package com.runner.academy.util

import com.runner.academy.data.TrackData
import com.runner.academy.data.TrackPoint
import com.runner.academy.gpsreplay.SyntheticRun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Date

/** Which tracks may hold a record (ticket 04, cases 1–6). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RecordEligibilityTest {

    /** A recorded-looking street run: 3 km at 3.3 m/s with GPS noise. */
    private val noisyRun = SyntheticRun(route = listOf(0.0 to 0.0, 3_000.0 to 0.0)).rawPoints()

    private fun track(points: List<TrackPoint>) = RouteTimeAligner.buildTrackData(points)

    private fun gpx(withTime: Boolean): String {
        val points = (0 until 40).joinToString("\n") { i ->
            // Uneven steps and times, as a real recording has them
            val lon = 37.6176 + i * 0.00005 + (i % 3) * 0.000007
            val time = if (withTime || i == 0) "<time>2024-01-01T10:%02d:%02dZ</time>".format(i * 4 / 60, i * 4 % 60) else ""
            """<trkpt lat="55.7558" lon="$lon">$time</trkpt>"""
        }
        return """
            <gpx version="1.1"><trk><trkseg>
            $points
            </trkseg></trk></gpx>
        """.trimIndent()
    }

    private fun parsedTrack(xml: String): TrackData = TrackDataJson.parse(GpxImporter.parseGpx(xml).trackData)!!

    @Test
    fun `1 a GPX without time is not trusted`() {
        val track = parsedTrack(gpx(withTime = false))

        assertEquals(true, track.timeSynthetic)
        assertFalse(RecordEligibility.isTrusted(track))
    }

    @Test
    fun `2 a GPX with time is trusted`() {
        val track = parsedTrack(gpx(withTime = true))

        assertNull(track.timeSynthetic)
        assertTrue(RecordEligibility.isTrusted(track))
    }

    @Test
    fun `3 a route timed uniformly by the form is not trusted`() {
        val result = WorkoutTrackRebuilder.rebuild(
            originalTrackJson = null,
            selectedTrackJson = TrackDataJson.toJson(track(noisyRun)),
            originalDate = null,
            newDate = Date(1_700_000_000_000L),
            durationMs = 1_200_000L
        )

        assertEquals(WorkoutTrackRebuilder.TimeSource.UNIFORM, result.timeSource)
        assertFalse(RecordEligibility.isTrusted(TrackDataJson.parse(result.trackDataJson)!!))
    }

    @Test
    fun `4 an older uniformly timed route without the flag is caught by its constant speed`() {
        val uniform = RouteTimeAligner.distributeByDistance(track(noisyRun), 1_700_000_000_000L, 1_200_000L)!!
            .copy(timeSynthetic = null)

        assertFalse(RecordEligibility.isTrusted(uniform))
    }

    @Test
    fun `a constant speed over fewer than 20 steps is not enough to tell`() {
        val uniform = RouteTimeAligner.distributeByDistance(track(noisyRun.take(20)), 0L, 60_000L)!!

        assertTrue(RecordEligibility.isTrusted(uniform))
    }

    @Test
    fun `5 a recorded run with GPS noise is trusted`() {
        assertTrue(RecordEligibility.isTrusted(track(noisyRun)))
    }

    @Test
    fun `6 a date shift keeps the flag`() {
        val synthetic = TrackDataJson.toJson(track(noisyRun).copy(timeSynthetic = true))
        val result = WorkoutTrackRebuilder.rebuild(
            originalTrackJson = synthetic,
            selectedTrackJson = synthetic,
            originalDate = Date(1_000_000L),
            newDate = Date(1_000_000L + 86_400_000L),
            durationMs = 1_200_000L
        )

        assertEquals(WorkoutTrackRebuilder.TimeSource.SHIFTED, result.timeSource)
        val shifted = TrackDataJson.parse(result.trackDataJson)!!
        assertEquals(true, shifted.timeSynthetic)
        assertFalse(RecordEligibility.isTrusted(shifted))
    }
}
