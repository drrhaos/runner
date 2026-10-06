package com.runner.academy.gpsreplay

import com.google.gson.GsonBuilder
import com.runner.academy.data.GpsStatus
import com.runner.academy.data.PauseInterval
import com.runner.academy.data.TrackPoint
import com.runner.academy.data.Workout
import com.runner.academy.data.WorkoutType
import com.runner.academy.service.ActiveWorkoutCheckpoint
import com.runner.academy.service.AutoPauseDetector
import com.runner.academy.service.AutoPauseEvent
import com.runner.academy.service.GpsLocationProcessor
import com.runner.academy.service.GpsStatusWatchdog
import com.runner.academy.service.WorkoutSessionManager
import com.runner.academy.ui.tracking.LiveWorkoutBuilder
import com.runner.academy.util.StepDistanceEstimator
import com.runner.academy.util.TrackSanitizer

/** How the replayed session is set up. */
data class ReplaySettings(
    /** The auto-pause setting. */
    val autoPause: Boolean = false,
    /** An interval workout (segments set): no auto-pause there, whatever [autoPause] says. */
    val intervals: Boolean = false,
    /** The step sensor is there and allowed; false: fixes and ticks carry no steps. */
    val stepsAvailable: Boolean = true,
    /** The run's frozen stride for bridges, as the service gives the processor. */
    val stepDistance: StepDistanceEstimator? = null,
    val type: WorkoutType = WorkoutType.EASY_RUN,
    val userWeightKg: Float = 70f
)

/** What the runner (or the system) does during the replay; seconds from the start. */
sealed class ReplayEvent {
    /** Stop pressed (default: at the end of the run). */
    data class Stop(val atSec: Int) : ReplayEvent()

    /** Pause pressed at [atSec], Resume [sec] later. */
    data class ManualPause(val atSec: Int, val sec: Int) : ReplayEvent()

    /**
     * The process dies at [atSec] and the service restores from the checkpoint [downSec] later
     * (fixes and steps in between are lost).
     */
    data class KillAndRestore(val atSec: Int, val downSec: Int = 0) : ReplayEvent()
}

data class SessionReplayResult(
    /** What the screen shows at Stop. */
    val elapsedMs: Long,
    val movingMs: Long,
    /** The live distance at Stop. */
    val distanceM: Double,
    val pauses: List<PauseInterval>,
    /** The row the save path builds from the stopped session. */
    val savedWorkout: Workout
)

/**
 * Replays a [SyntheticRun] through the service's session glue on simulated time: the 1 s timer
 * (silence by steps, the [AutoPauseDetector] tick, clock tick), the per-fix branch of
 * `WorkoutTrackingService.updateLocation` into [WorkoutSessionManager] and the detector, the
 * periodic GPS watchdog ([GpsStatusWatchdog], screen on: every 2 s, lost after 3 × 5 s), manual
 * pause / resume (GPS off, steps dropped by the step tracker, the detector reset), a process
 * restart through a Gson round trip of [ActiveWorkoutCheckpoint] (an open auto-pause restores
 * the detector paused), and Stop through the VM's save path ([LiveWorkoutBuilder]).
 *
 * Simulated time is both the wall and the monotonic clock (a fix's time is its
 * `elapsedRealtime`), so the service's mono → wall conversion is the identity here.
 *
 * Not modelled: the unreliable-signal latch, the lastKnown pull of the watchdog and the stride
 * learner. Keep in sync with the service when that glue changes, like [GpsReplay.live].
 */
object SessionReplay {

    private const val TICK_MS = 1_000L
    /** The watchdog's period and timeout with the screen on (`WorkoutTrackingService`). */
    private const val WATCHDOG_EVERY_TICKS = 2
    private const val LOST_TIMEOUT_MS = 5_000L
    private val gson = GsonBuilder().create()

    fun run(
        run: SyntheticRun,
        settings: ReplaySettings = ReplaySettings(),
        events: List<ReplayEvent> = emptyList()
    ): SessionReplayResult {
        val start = run.startTimeMs
        val stopSec = events.filterIsInstance<ReplayEvent.Stop>().minOfOrNull { it.atSec } ?: run.durationSec
        val pauses = run.manualPauseSeconds +
            events.filterIsInstance<ReplayEvent.ManualPause>().map { it.atSec to it.atSec + it.sec }
        val pauseStarts = pauses.map { it.first }.toSet()
        val pauseEnds = pauses.map { it.second }.toSet()
        val kills = events.filterIsInstance<ReplayEvent.KillAndRestore>().associateBy { it.atSec }
        val fixes = ArrayDeque(run.rawPoints())

        val live = Live(run, settings)
        live.start(start)
        var deadUntilSec = -1
        var killedAtMs = 0L
        var checkpointJson: String? = null

        for (sec in 0..stopSec) {
            val now = start + sec * TICK_MS
            when {
                sec < deadUntilSec -> {
                    fixes.dropWhile(now + TICK_MS)
                    continue
                }
                sec == deadUntilSec -> live.restore(checkpointJson!!, killedAtMs, now)
                sec > 0 && live.tracking -> live.tick(now)
            }
            if (sec == stopSec) break
            if (sec in pauseEnds && live.paused) live.resume(now)
            if (sec in pauseStarts && live.tracking) live.pause(now)
            val kill = kills[sec]
            if (kill != null) {
                checkpointJson = live.checkpoint(now)
                killedAtMs = now
                if (kill.downSec > 0) {
                    deadUntilSec = sec + kill.downSec
                    fixes.dropWhile(now + TICK_MS)
                    continue
                }
                live.restore(checkpointJson!!, now, now)
            }
            while (fixes.isNotEmpty() && fixes.first().timestamp < now + TICK_MS) {
                val fix = fixes.removeFirst()
                if (live.tracking) live.onFix(fix)
            }
        }
        return live.stop(start + stopSec * TICK_MS)
    }

    private fun ArrayDeque<TrackPoint>.dropWhile(beforeMs: Long) {
        while (isNotEmpty() && first().timestamp < beforeMs) removeFirst()
    }

    /** One service process: its session manager, fix processor, auto-pause detector and watchdog. */
    private class Live(private val run: SyntheticRun, private val settings: ReplaySettings) {
        var manager = WorkoutSessionManager()
        var processor = GpsLocationProcessor()
        var detector = AutoPauseDetector()
        /** `isCurrentlyTracking`: running and not paused. */
        var tracking = false
        val paused: Boolean get() = manager.getSession().isPaused

        /** Sensor steps the step tracker never counted (pauses, a dead process). */
        private var droppedSteps = 0
        private var stepsAtPause: Int? = null

        // The watchdog's clocks (`lastLocationTime`, `lastAnyFixTime`) and its 2 s loop
        private var lastGoodFixMs = 0L
        private var lastAnyFixMs = 0L
        private var watchdogTicks = 0

        private fun sensorSteps(timeMs: Long): Pair<Int, Float?>? =
            if (settings.stepsAvailable) run.stepsAt(timeMs) else null

        private fun trackerSteps(timeMs: Long): Pair<Int, Float?>? =
            sensorSteps(timeMs)?.let { (steps, cadence) -> (steps - droppedSteps) to cadence }

        fun start(now: Long) {
            lastGoodFixMs = 0L
            lastAnyFixMs = 0L
            watchdogTicks = 0
            detector.reset()
            processor.reset(workoutType = settings.type, stepDistance = settings.stepDistance)
            manager.startNewSession(initialGpsStatus = GpsStatus.SEARCHING, now = now)
            tracking = true
        }

        /**
         * The 1 s timer: `countSilenceBySteps()`, `tickAutoPause()`, then `tickElapsedTime`.
         * The watchdog runs on its own 2 s loop, modelled here on every second tick.
         */
        fun tick(now: Long) {
            if (++watchdogTicks % WATCHDOG_EVERY_TICKS == 0) watchdog(now)
            trackerSteps(now)?.let { (steps, cadence) ->
                val added = processor.countSilence(now, steps, cadence)
                manager.setOpenStepMeters(processor.pendingStepMeters)
                manager.addStepDistance(added, settings.userWeightKg)
            }
            tickAutoPause(now)
            manager.tickElapsedTime(broadcast = false, now = now)
        }

        /** `resolveGpsStatusDuringWorkout()` with the screen on (permission granted). */
        private fun watchdog(now: Long) {
            val next = GpsStatusWatchdog.resolve(
                current = manager.getSession().gpsStatus,
                nowMs = now,
                lastGoodFixMs = lastGoodFixMs,
                lastAnyFixMs = lastAnyFixMs,
                lostTimeoutMs = LOST_TIMEOUT_MS
            ) ?: return
            manager.updateGpsStatus(next)
        }

        /** `tickAutoPause()`: mono and wall are the same clock here. */
        private fun tickAutoPause(now: Long) {
            val session = manager.getSession()
            if (!session.isTracking || session.isPaused) return
            if (!settings.autoPause || settings.intervals) return
            val gpsLost = session.gpsStatus == GpsStatus.LOST || session.gpsStatus == GpsStatus.DENIED
            when (val event = detector.tick(now, trackerSteps(now)?.first, gpsLost)) {
                is AutoPauseEvent.Pause -> manager.enterAutoPause(event.atMono, now)
                is AutoPauseEvent.Resume -> manager.exitAutoPause(event.atMono, now)
                null -> Unit
            }
        }

        /** The tracking branch of `updateLocation`. */
        fun onFix(fix: TrackPoint) {
            val session = manager.getSession()
            val location = TrackSanitizer.toLocation(fix)
            val steps = if (settings.stepsAvailable) fix.steps?.let { it - droppedSteps } else null
            val cadence = if (settings.stepsAvailable) fix.cadence else null
            val result = processor.processLocation(
                location,
                session.trackPoints.toMutableList(),
                session.trackDataPoints.toMutableList(),
                session.rawTrackDataPoints.toMutableList(),
                resumeAfterGap = session.gpsStatus == GpsStatus.LOST,
                steps = steps,
                cadence = cadence
            )
            manager.setOpenStepMeters(processor.pendingStepMeters)
            val good = when (result) {
                is GpsLocationProcessor.ProcessResult.Accepted -> true
                is GpsLocationProcessor.ProcessResult.Rejected -> result.refreshGapClock
            }
            lastAnyFixMs = fix.timestamp
            if (good) lastGoodFixMs = fix.timestamp
            detector.onFix(fix.timestamp, if (location.hasSpeed()) location.speed else null, usable = good)
            when (result) {
                is GpsLocationProcessor.ProcessResult.Accepted -> manager.updateMetricsFromLocation(
                    segmentDistanceMeters = result.distanceDeltaMeters,
                    trackPoints = result.trackPoints,
                    trackDataPoints = result.trackDataPoints,
                    rawTrackDataPoints = result.rawTrackDataPoints,
                    userWeightKg = settings.userWeightKg,
                    currentLocation = result.filteredLocation,
                    gpsStatus = GpsStatus.FOUND
                )
                is GpsLocationProcessor.ProcessResult.Rejected -> when {
                    result.refreshGapClock -> manager.updateLocationOnly(
                        currentLocation = location,
                        rawTrackDataPoints = result.rawTrackDataPoints
                    )
                    result.retractedStart -> manager.updateLocationOnly(
                        currentLocation = session.currentLocation ?: location,
                        rawTrackDataPoints = result.rawTrackDataPoints,
                        trackPoints = result.trackPoints,
                        trackDataPoints = result.trackDataPoints,
                        addedDistanceMeters = result.distanceDeltaMeters,
                        userWeightKg = settings.userWeightKg
                    )
                    else -> manager.updateLocationOnly(
                        currentLocation = session.currentLocation ?: location,
                        rawTrackDataPoints = result.rawTrackDataPoints,
                        addedDistanceMeters = result.distanceDeltaMeters,
                        userWeightKg = settings.userWeightKg
                    )
                }
            }
        }

        /** `pauseWorkout()`: GPS and timer stop, the step tracker drops what comes next. */
        fun pause(now: Long) {
            tracking = false
            stepsAtPause = sensorSteps(now)?.first
            manager.pause(now)
        }

        /** `resumeWorkout()`. */
        fun resume(now: Long) {
            val before = stepsAtPause
            val after = sensorSteps(now)?.first
            if (before != null && after != null) droppedSteps += after - before
            stepsAtPause = null
            processor.onResume()
            detector.reset()
            manager.resume(now)
            // The watchdog's grace after the pause, and its loop restarts
            if (lastGoodFixMs != 0L) lastGoodFixMs = now
            if (lastAnyFixMs != 0L) lastAnyFixMs = now
            watchdogTicks = 0
            tracking = true
        }

        /** `maybeSaveCheckpoint` as written to disk. */
        fun checkpoint(now: Long): String {
            val checkpoint = ActiveWorkoutCheckpoint.fromSession(
                session = manager.getSession(),
                workoutType = settings.type,
                modeSelection = null,
                intervalSegmentsJson = null,
                intervalCursor = null,
                lastLocationTime = lastGoodFixMs,
                lastUpdateTime = manager.getLastUpdateTime(),
                steps = trackerSteps(now)?.first,
                pendingStepMeters = processor.pendingStepMeters
            )
            return gson.toJson(checkpoint)
        }

        /** A new process: `restoreFromCheckpoint()` at [now] from what was saved at [killedAt]. */
        fun restore(json: String, killedAt: Long, now: Long) {
            val checkpoint = gson.fromJson(json, ActiveWorkoutCheckpoint::class.java)
            // The new step tracker starts from the saved count: steps while dead are lost
            if (stepsAtPause == null) {
                val before = sensorSteps(killedAt)?.first
                val after = sensorSteps(now)?.first
                if (before != null && after != null) droppedSteps += after - before
            }
            processor = GpsLocationProcessor().apply {
                reset(
                    workoutType = checkpoint.resolvedWorkoutType(),
                    anchor = checkpoint.toSession().currentLocation,
                    anchorSteps = checkpoint.trackDataPoints.lastOrNull()?.steps,
                    pendingMeters = checkpoint.pendingStepMeters,
                    stepDistance = settings.stepDistance
                )
            }
            val restored = checkpoint.toRestoredSession(now)
            manager = WorkoutSessionManager().apply {
                restoreSession(restored, checkpoint.lastUpdateTime)
                setOpenStepMeters(processor.pendingStepMeters)
            }
            // An open auto-pause stays open: the new detector waits for fresh steps or speed
            detector = AutoPauseDetector(paused = restored.autoPaused)
            lastGoodFixMs = checkpoint.lastLocationTime
            lastAnyFixMs = checkpoint.lastLocationTime
            watchdogTicks = 0
            tracking = !restored.isPaused
        }

        /** Stop: the VM freezes the session at Stop and saves it. */
        fun stop(now: Long): SessionReplayResult {
            val stopped = LiveWorkoutBuilder.stopped(manager.getSession(), now)
            val workout = LiveWorkoutBuilder.build(
                session = stopped,
                workoutType = settings.type,
                manualDistanceKm = null,
                intervalSegmentsJson = null,
                stepDistance = settings.stepDistance,
                userWeightKg = settings.userWeightKg,
                stopAt = now
            ) ?: error("nothing saved")
            return SessionReplayResult(
                elapsedMs = stopped.currentTime,
                movingMs = stopped.movingTime,
                distanceM = stopped.distance * 1000.0,
                pauses = stopped.clock.pauses,
                savedWorkout = workout
            )
        }
    }
}
