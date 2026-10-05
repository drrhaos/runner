package com.runner.academy.util

import com.runner.academy.data.TrackData
import com.runner.academy.data.TrackPoint
import com.runner.academy.data.WorkoutType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DisplayTrackTest {

    /** ~10 m steps east every 3 s. */
    private val points: List<TrackPoint> = (0 until 40).map { i ->
        TrackPoint(55.75, 37.60 + i * 0.00016, 1_000_000L + i * 3_000L, 5f, 3f, 150.0)
    }

    private fun json(points: List<TrackPoint>) =
        TrackDataJson.toJson(TrackData(points, 0f, 0L, 0f, 0f, points.first().timestamp, points.last().timestamp))

    @Test
    fun `a clean track is shown as stored`() {
        val stored = json(points)

        assertEquals(TrackDataJson.parse(stored), DisplayTrack.of(stored, WorkoutType.EASY_RUN))
    }

    @Test
    fun `a track with many outliers is cleaned for display`() {
        val withOutliers = points.mapIndexed { i, p -> if (i % 10 == 5) p.copy(longitude = 37.70) else p }

        val shown = DisplayTrack.of(json(withOutliers), WorkoutType.EASY_RUN)!!

        assertTrue("outliers dropped", shown.points.none { it.longitude > 37.65 })
        assertEquals(TrackGeometry.totalDistanceMeters(shown.points), shown.totalDistance, 0.5f)
    }

    @Test
    fun `no or broken track has nothing to show`() {
        assertNull(DisplayTrack.of(null, WorkoutType.EASY_RUN))
        assertNull(DisplayTrack.of("{not json", WorkoutType.EASY_RUN))
    }
}
