package com.runner.academy.ui.tracking

import com.runner.academy.data.ElevationSource
import com.runner.academy.data.PauseInterval
import com.runner.academy.data.PauseKind
import com.runner.academy.data.TrackPoint
import com.runner.academy.data.WorkoutSession
import com.runner.academy.data.WorkoutType
import com.runner.academy.service.SessionClock
import com.runner.academy.util.TrackDataJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LiveWorkoutBuilderTest {

    private val t0 = 1_700_000_000_000L
    private fun at(sec: Int) = t0 + sec * 1_000L

    /** 1 Hz points due north at 3 m/s for [seconds]. */
    private fun points(seconds: Int) = (0..seconds).map { s ->
        TrackPoint(55.75 + s * 3.0 / 111_320.0, 37.6, at(s), 5f, 3f, 150.0)
    }

    private fun session(clock: SessionClock, currentTime: Long) = WorkoutSession(
        isTracking = true,
        startTime = t0,
        currentTime = currentTime,
        movingTime = currentTime,
        rawTrackDataPoints = points(100),
        clock = clock.state
    )

    private fun build(session: WorkoutSession, stopAt: Long) = LiveWorkoutBuilder.build(
        session, WorkoutType.EASY_RUN, null, null, null, 70f, stopAt
    )!!

    @Test
    fun `duration, moving time and pauses come from the clock stopped at Stop`() {
        val clock = SessionClock().apply {
            start(t0)
            pauseManual(at(40))
            resumeManual(at(70))
            enterAutoPause(at(100))
        }

        val workout = build(session(clock, 99_000L), stopAt = at(130))
        val track = TrackDataJson.parse(workout.trackData)!!

        assertEquals(100_000L, workout.duration)
        assertEquals(70_000L, workout.movingDuration)
        assertEquals(
            listOf(PauseInterval(at(40), at(70), PauseKind.MANUAL), PauseInterval(at(100), at(130), PauseKind.AUTO)),
            track.pauses
        )
        assertEquals(at(130), track.endTime)
    }

    @Test
    fun `a session stopped earlier keeps its stop however late the save`() {
        val clock = SessionClock().apply { start(t0) }
        val stopped = LiveWorkoutBuilder.stopped(session(clock, 59_000L), at(60))

        val workout = build(stopped, stopAt = at(600))

        assertEquals(60_000L, stopped.currentTime)
        assertEquals(60_000L, workout.duration)
        assertEquals(60_000L, workout.movingDuration)
        assertEquals(emptyList<PauseInterval>(), TrackDataJson.parse(workout.trackData)!!.pauses)
    }

    @Test
    fun `a session without a clock keeps the ticked time and writes no pauses`() {
        val session = WorkoutSession(
            isTracking = true, startTime = t0, currentTime = 100_000L, movingTime = 0L,
            rawTrackDataPoints = points(100)
        )

        val workout = build(session, stopAt = at(500))

        assertEquals(100_000L, workout.duration)
        assertEquals(100_000L, workout.movingDuration)
        assertNull(TrackDataJson.parse(workout.trackData)!!.pauses)
    }

    @Test
    fun `a recording with altitudes declares them GPS`() {
        val workout = build(session(SessionClock().apply { start(t0) }, 100_000L), stopAt = at(100))

        assertEquals(ElevationSource.GPS, TrackDataJson.parse(workout.trackData)!!.elevationSource)
    }

    @Test
    fun `a recording without altitudes stores none, not zeros`() {
        val session = session(SessionClock().apply { start(t0) }, 100_000L).let { s ->
            s.copy(rawTrackDataPoints = s.rawTrackDataPoints.map { it.copy(altitude = null) })
        }

        val track = TrackDataJson.parse(build(session, stopAt = at(100)).trackData)!!

        assertNull(track.elevationSource)
        assertEquals(ElevationSource.NONE, ElevationSource.of(track))
        assertTrue(track.points.all { it.altitude == null })
    }
}
