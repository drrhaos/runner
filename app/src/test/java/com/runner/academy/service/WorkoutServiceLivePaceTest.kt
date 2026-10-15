package com.runner.academy.service

import android.content.Intent
import com.runner.academy.data.LivePace
import com.runner.academy.data.TrackPoint
import com.runner.academy.gpsreplay.SyntheticRun
import com.runner.academy.sensor.FakeHeartRateSensor
import com.runner.academy.sensor.SensorLink
import com.runner.academy.util.TrackSanitizer
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

/**
 * The live pace on the workout timer: the service feeds its [LivePaceTracker] on each tick
 * with its own clock ([WorkoutTrackingService.sessionClockMs]) and puts the pace in the session.
 * No step sensor here: the pace is by GPS only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorkoutServiceLivePaceTest {

    private val app = RuntimeEnvironment.getApplication()
    private var nowMs = SyntheticRun.START_TIME
    private val controllers = mutableListOf<ServiceController<WorkoutTrackingService>>()

    /** 5 km straight at 5:00 per km, a fix a second, no GPS noise (the glue, not the accuracy). */
    private val fixes: List<TrackPoint> = SyntheticRun(
        route = listOf(0.0 to 0.0, 5_000.0 to 0.0),
        speedMps = 1_000.0 / 300.0,
        noiseInnovationM = 0.0
    ).rawPoints()

    private fun service(): WorkoutTrackingService {
        val controller = Robolectric.buildService(WorkoutTrackingService::class.java)
        controllers += controller
        val service = controller.get()
        service.heartRateSource = HeartRateSource(FakeHeartRateSensor(SensorLink.IDLE), MutableStateFlow(emptyMap())) { true }
        service.sessionClockMs = { nowMs }
        controller.create()
        return service
    }

    @After
    fun tearDown() {
        controllers.forEach { controller ->
            val service = controller.get()
            if (service.getCurrentSession().isTracking || service.getCurrentSession().isPaused) service.stopWorkout()
            controller.destroy()
        }
        ActiveWorkoutStore(app).clear()
    }

    private val second: Int get() = ((nowMs - SyntheticRun.START_TIME) / 1_000L).toInt()

    /** The fix of the current second (if [withFix]), then the timer's tick a second later. */
    private fun WorkoutTrackingService.runFor(seconds: Int, withFix: Boolean = true): List<LivePace?> =
        List(seconds) {
            if (withFix) {
                val fix = fixes[second]
                updateLocation(TrackSanitizer.toLocation(fix).apply { elapsedRealtimeNanos = fix.timestamp * 1_000_000L })
            }
            nowMs += 1_000L
            tickWorkout()
            getCurrentSession().livePace
        }

    private fun assertFiveMinutes(pace: LivePace?) {
        assertNotNull("pace", pace)
        assertEquals(5f, pace!!.minPerKm, 1f / 60f)
        assertFalse(pace.approximate)
    }

    @Test
    fun `no pace for the first 15 s, then the GPS pace of the window`() {
        val service = service()
        service.startWorkout()

        val paces = service.runFor(40)

        assertTrue("first 14 s: ${paces.take(14)}", paces.take(14).all { it == null })
        paces.drop(16).forEach(::assertFiveMinutes)
    }

    @Test
    fun `GPS gone without steps - no pace once the last fix is over 5 s old`() {
        val service = service()
        service.startWorkout()
        service.runFor(40)

        val silent = service.runFor(10, withFix = false)

        assertNotNull("4 s old", silent[3])
        assertTrue("from 6 s: ${silent.drop(5)}", silent.drop(5).all { it == null })
    }

    @Test
    fun `a manual pause clears the pace and the resume starts the window over`() {
        val service = service()
        service.startWorkout()
        service.runFor(40)

        service.pauseWorkout()
        assertNull(service.getCurrentSession().livePace)
        nowMs += 60_000L
        service.resumeWorkout()
        val after = service.runFor(30)

        assertTrue("first 14 s: ${after.take(14)}", after.take(14).all { it == null })
        after.drop(16).forEach(::assertFiveMinutes)
    }

    @Test
    fun `the pace is not checkpointed - a restore starts the window over`() {
        val service = service()
        service.startWorkout()
        service.runFor(40)
        assertNotNull(service.getCurrentSession().livePace)

        // The process dies; a new one restores from what the last tick saved
        val store = ActiveWorkoutStore(app)
        for (attempt in 1..100) {
            if (store.load() != null) break
            Thread.sleep(20)
        }
        val restored = service()
        restored.onStartCommand(
            Intent(app, WorkoutTrackingService::class.java).setAction(WorkoutTrackingService.ACTION_RESTORE_WORKOUT), 0, 1
        )
        assertTrue(restored.getCurrentSession().isTracking)
        assertNull(restored.getCurrentSession().livePace)

        val after = restored.runFor(30)
        assertTrue("first 14 s: ${after.take(14)}", after.take(14).all { it == null })
        assertFiveMinutes(after.last())
    }
}
