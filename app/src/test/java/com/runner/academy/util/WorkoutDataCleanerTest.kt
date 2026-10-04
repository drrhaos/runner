package com.runner.academy.util

import com.runner.academy.data.TrackData
import com.runner.academy.data.TrackPoint
import com.runner.academy.data.WorkoutType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WorkoutDataCleanerTest {

    /** ~10 m steps east every 3 s; the step into index 20 closes a dropped stretch (bridge). */
    private val points: List<TrackPoint> = (0 until 40).map { i ->
        val bridged = i >= 20
        TrackPoint(
            latitude = 55.75,
            longitude = 37.60 + i * 0.00016 + if (bridged) 0.0016 else 0.0,
            timestamp = 1_000_000L + i * 3_000L + if (bridged) 60_000L else 0L,
            accuracy = 5f,
            speed = 3f,
            altitude = 150.0,
            afterGap = i == 20,
            bridgeMeters = if (i == 20) 130f else null,
            steps = i * 5,
            cadence = 170f
        )
    }

    @Test
    fun `cleaning keeps a bridge and counts its distance`() {
        val track = TrackData(points, 0f, 0L, 0f, 0f, points.first().timestamp, points.last().timestamp)

        val cleaned = WorkoutDataCleaner.cleanTrackData(track, WorkoutType.EASY_RUN)

        val bridge = cleaned.points.single { it.afterGap }
        assertEquals(130f, bridge.bridgeMeters!!, 0.01f)
        assertEquals(TrackGeometry.totalDistanceMeters(points), cleaned.totalDistance, 0.5f)
        assertTrue("step counts survive", cleaned.points.all { it.steps != null && it.cadence != null })
    }

    @Test
    fun `the tail survives when cleaning drops the last point`() {
        val outlierLast = points.last().copy(longitude = 37.70, tailMeters = 240f)
        val withTail = points.dropLast(1) + outlierLast
        val track = TrackData(withTail, 0f, 0L, 0f, 0f, withTail.first().timestamp, withTail.last().timestamp)

        val cleaned = WorkoutDataCleaner.cleanTrackData(track, WorkoutType.EASY_RUN)

        assertTrue("the far last point is dropped", cleaned.points.size < withTail.size)
        assertEquals(240f, TrackGeometry.tailMeters(cleaned.points), 0.01f)
    }
}
