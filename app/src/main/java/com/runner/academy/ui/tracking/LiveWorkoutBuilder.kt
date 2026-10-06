package com.runner.academy.ui.tracking

import com.runner.academy.data.TrackData
import com.runner.academy.data.Workout
import com.runner.academy.data.WorkoutSession
import com.runner.academy.data.WorkoutType
import com.runner.academy.service.SessionClock
import com.runner.academy.util.FormatUtils
import com.runner.academy.util.PaceMath
import com.runner.academy.util.SpeedPaceCalculator
import com.runner.academy.util.StepDistanceEstimator
import com.runner.academy.util.TrackDataJson
import com.runner.academy.util.TrackSanitizer
import java.util.Date

/**
 * The save path of a live recording, pure: the session as the service left it → the row to
 * insert. The time comes from the session clock stopped at Stop, so the saved duration and
 * moving time are what the run showed (live = saved), and the track carries its pauses.
 */
object LiveWorkoutBuilder {

    /**
     * [session] at Stop: the clock stops at [now] (no-op if stopped) and the times freeze there.
     * The tracking flags stay as they are (the screen follows its own state).
     */
    fun stopped(session: WorkoutSession, now: Long): WorkoutSession {
        if (session.clock.startedAt <= 0L) return session
        val clock = SessionClock(session.clock).also { it.stop(now) }
        return session.copy(
            currentTime = clock.elapsedMs(now),
            movingTime = clock.movingMs(now),
            autoPaused = false,
            clock = clock.state
        )
    }

    /**
     * @param stepDistance the run's frozen stride ([WorkoutSession.strideModelState]), as live
     * @param stopAt the moment of Stop when the clock was not stopped yet
     * @return null when there is nothing to save (no time)
     */
    fun build(
        session: WorkoutSession,
        workoutType: WorkoutType,
        manualDistanceKm: Float?,
        intervalSegmentsJson: String?,
        stepDistance: StepDistanceEstimator?,
        userWeightKg: Float,
        stopAt: Long
    ): Workout? {
        val clock = SessionClock(session.clock)
        val clockStarted = session.clock.startedAt > 0L
        if (clockStarted) clock.stop(stopAt)
        val endTime = clock.state.stoppedAt ?: stopAt
        // A session without a clock (should not happen) keeps the last ticked time
        val durationMs = if (clockStarted) clock.elapsedMs(endTime) else session.currentTime
        val movingDurationMs = if (clockStarted) {
            clock.movingMs(endTime)
        } else {
            session.movingTime.takeIf { it in 1..durationMs } ?: durationMs
        }
        if (durationMs <= 0L) return null

        val sourcePoints = session.rawTrackDataPoints.ifEmpty { session.trackDataPoints }
        // An open silence or false signal at Stop keeps its step distance as the track's tail
        val sanitizedPoints = TrackSanitizer.sanitize(sourcePoints, workoutType, stepDistance, session.openStepMeters)
        val hasTrack = sanitizedPoints.size >= 2

        val totalDistanceMeters = when {
            manualDistanceKm != null && manualDistanceKm >= 0f -> manualDistanceKm * 1000f
            hasTrack -> SpeedPaceCalculator.totalDistanceMeters(sanitizedPoints)
            session.distance > 0f -> session.distance * 1000f
            else -> 0f
        }
        val totalDistanceKm = totalDistanceMeters / 1000f
        val avgSpeedMps = if (totalDistanceMeters > 0f) {
            SpeedPaceCalculator.averageSpeedMs(totalDistanceMeters, durationMs)
        } else {
            0f
        }
        val maxSpeedMps = if (hasTrack && manualDistanceKm == null) {
            SpeedPaceCalculator.maxDerivedSpeedMs(sanitizedPoints)
        } else {
            0f
        }

        val trackDataJson = if (hasTrack && manualDistanceKm == null && totalDistanceMeters > 0f) {
            TrackDataJson.toJson(
                TrackData(
                    points = sanitizedPoints,
                    totalDistance = totalDistanceMeters,
                    totalDuration = durationMs,
                    avgSpeed = avgSpeedMps,
                    maxSpeed = maxSpeedMps,
                    startTime = session.startTime,
                    endTime = endTime,
                    // Open pauses are closed by the Stop; null only without a clock (unknown)
                    pauses = if (clockStarted) clock.pauses() else null
                )
            )
        } else {
            null
        }

        return Workout(
            date = Date(session.startTime),
            distance = totalDistanceKm,
            duration = durationMs,
            movingDuration = movingDurationMs,
            avgPace = PaceMath.avgPace(totalDistanceKm, movingDurationMs),
            calories = FormatUtils.calculateCalories(totalDistanceKm, userWeightKg),
            notes = null,
            type = workoutType,
            trackData = trackDataJson,
            intervalSegmentsJson = intervalSegmentsJson
        )
    }
}
