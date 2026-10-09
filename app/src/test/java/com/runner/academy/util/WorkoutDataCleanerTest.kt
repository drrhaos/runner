package com.runner.academy.util

import com.runner.academy.data.ElevationSource
import com.runner.academy.data.PauseInterval
import com.runner.academy.data.PauseKind
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
            cadence = 170f,
            baroM = 100f + i
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
        assertTrue("pressure altitudes survive", cleaned.points.all { it.baroM == 100f + it.steps!! / 5 })
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

    @Test
    fun `cleaning keeps the track fields`() {
        val pauses = listOf(PauseInterval(1_010_000L, 1_040_000L, PauseKind.AUTO))
        val outlier = points.last().copy(longitude = 37.70)
        val track = TrackData(points.dropLast(1) + outlier, 0f, 0L, 0f, 0f, 0L, null).copy(
            pauses = pauses,
            timeSynthetic = true,
            elevationSource = ElevationSource.GPS
        )

        val cleaned = WorkoutDataCleaner.cleanTrackData(track, WorkoutType.EASY_RUN)

        assertTrue("cleaning rebuilt the track", cleaned.points.size < track.points.size)
        assertEquals(pauses, cleaned.pauses)
        assertEquals(true, cleaned.timeSynthetic)
        assertEquals(ElevationSource.GPS, cleaned.elevationSource)
    }

    @Test
    fun `cleaning keeps a missing altitude missing`() {
        val noAltitude = points.mapIndexed { i, p -> if (i % 2 == 0) p.copy(altitude = null) else p }
        val outlier = noAltitude.last().copy(longitude = 37.70)
        val track = TrackData(noAltitude.dropLast(1) + outlier, 0f, 0L, 0f, 0f, 0L, null)

        val cleaned = WorkoutDataCleaner.cleanTrackData(track, WorkoutType.EASY_RUN)

        assertTrue("cleaning rebuilt the track", cleaned.points.size < track.points.size)
        assertTrue("no 0.0 for a missing altitude", cleaned.points.none { it.altitude == 0.0 })
        assertEquals(noAltitude.dropLast(1).map { it.altitude }, cleaned.points.map { it.altitude })
    }
}
