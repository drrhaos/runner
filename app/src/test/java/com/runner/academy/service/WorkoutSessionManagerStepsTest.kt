package com.runner.academy.service

import android.location.Location
import com.runner.academy.data.GpsStatus
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WorkoutSessionManagerStepsTest {

    private val manager = WorkoutSessionManager().apply { startNewSession(strideModelState = "state") }

    @Test
    fun `a dropped fix can add step distance and set the status`() {
        manager.updateLocationOnly(
            currentLocation = Location("test"),
            rawTrackDataPoints = emptyList(),
            addedDistanceMeters = 250f,
            userWeightKg = 70f,
            gpsStatus = GpsStatus.UNRELIABLE
        )

        val session = manager.getSession()
        assertEquals(0.25f, session.distance, 0.0001f)
        assertEquals(GpsStatus.UNRELIABLE, session.gpsStatus)
        assertEquals("state", session.strideModelState)
    }

    @Test
    fun `a dropped fix keeps the status by default`() {
        manager.updateGpsStatus(GpsStatus.LOST)
        manager.updateLocationOnly(Location("test"), emptyList())
        assertEquals(GpsStatus.LOST, manager.getSession().gpsStatus)
        assertEquals(0f, manager.getSession().distance, 0f)
    }

    @Test
    fun `an accepted fix keeps the unreliable status while the latch holds`() {
        manager.updateMetricsFromLocation(10f, emptyList(), emptyList(), emptyList(), 70f, gpsStatus = GpsStatus.UNRELIABLE)
        assertEquals(GpsStatus.UNRELIABLE, manager.getSession().gpsStatus)
        manager.updateMetricsFromLocation(10f, emptyList(), emptyList(), emptyList(), 70f)
        assertEquals(GpsStatus.FOUND, manager.getSession().gpsStatus)
    }
}
