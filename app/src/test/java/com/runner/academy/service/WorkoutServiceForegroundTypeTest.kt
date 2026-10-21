package com.runner.academy.service

import android.content.pm.ServiceInfo
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The manifest declares `location|connectedDevice`; a start without explicit types would take
 * both and throw on 34+ without `BLUETOOTH_CONNECT`. Until the sensor joins the workout the
 * service starts as location only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorkoutServiceForegroundTypeTest {

    @Test
    fun `a workout starts in the foreground as location only`() {
        val controller = Robolectric.buildService(WorkoutTrackingService::class.java).create()
        val service = controller.get()
        try {
            service.startWorkout()
            assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION, service.foregroundServiceType)
        } finally {
            service.stopWorkout()
            controller.destroy()
        }
    }
}
