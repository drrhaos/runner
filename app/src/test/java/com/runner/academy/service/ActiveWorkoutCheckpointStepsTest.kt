package com.runner.academy.service

import com.google.gson.Gson
import com.runner.academy.data.TrackPoint
import com.runner.academy.data.WorkoutSession
import com.runner.academy.data.WorkoutType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ActiveWorkoutCheckpointStepsTest {

    private val gson = Gson()

    @Test
    fun `steps, pending step distance and the run stride survive a restore`() {
        val session = WorkoutSession(
            isTracking = true,
            distance = 1.2f,
            trackDataPoints = listOf(TrackPoint(55.0, 37.0, 1L, 5f, 3f, null, steps = 900, cadence = 170f)),
            strideModelState = "v1;1;0;1;0;1;3"
        )
        val checkpoint = ActiveWorkoutCheckpoint.fromSession(
            session, WorkoutType.EASY_RUN, null, null, null,
            lastLocationTime = 1L, lastUpdateTime = 1L,
            steps = 950, pendingStepMeters = 42f
        )

        val restored = gson.fromJson(gson.toJson(checkpoint), ActiveWorkoutCheckpoint::class.java)

        assertEquals(950, restored.steps)
        assertEquals(42f, restored.pendingStepMeters, 0f)
        assertEquals("v1;1;0;1;0;1;3", restored.toSession().strideModelState)
        assertEquals(900, restored.toSession().trackDataPoints.single().steps)
    }

    @Test
    fun `the pressure altitudes of the points survive a restore, an older point has none`() {
        val session = WorkoutSession(
            isTracking = true,
            trackDataPoints = listOf(TrackPoint(55.0, 37.0, 1L, 5f, 3f, 150.0, baroM = 101.5f)),
            rawTrackDataPoints = listOf(TrackPoint(55.0, 37.0, 1L, 5f, 3f, 150.0, baroM = 101.5f))
        )
        val checkpoint = ActiveWorkoutCheckpoint.fromSession(
            session, WorkoutType.EASY_RUN, null, null, null,
            lastLocationTime = 1L, lastUpdateTime = 1L, steps = null, pendingStepMeters = 0f
        )

        val restored = gson.fromJson(gson.toJson(checkpoint), ActiveWorkoutCheckpoint::class.java).toSession()

        assertEquals(101.5f, restored.trackDataPoints.single().baroM!!, 0f)
        assertEquals(101.5f, restored.rawTrackDataPoints.single().baroM!!, 0f)

        val older = """{"isTracking":true,"trackDataPoints":[{"latitude":55.0,"longitude":37.0,"timestamp":1}],"rawTrackDataPoints":[]}"""
        assertNull(gson.fromJson(older, ActiveWorkoutCheckpoint::class.java).toSession().trackDataPoints.single().baroM)
    }

    @Test
    fun `an older checkpoint without steps restores without them`() {
        val json = """{"isTracking":true,"distance":1.0,"trackDataPoints":[],"rawTrackDataPoints":[]}"""

        val restored = gson.fromJson(json, ActiveWorkoutCheckpoint::class.java)

        assertNull(restored.steps)
        assertEquals(0f, restored.pendingStepMeters, 0f)
        assertNull(restored.toSession().strideModelState)
    }
}
