package com.runner.academy.gpsreplay

import com.google.gson.GsonBuilder
import com.runner.academy.data.GpsStatus
import com.runner.academy.data.IntervalLive
import com.runner.academy.data.LivePace
import com.runner.academy.data.PauseInterval
import com.runner.academy.data.TrackPoint
import com.runner.academy.data.Workout
import com.runner.academy.data.WorkoutType
import com.runner.academy.sensor.FakeHeartRateSensor
import com.runner.academy.sensor.SensorLink
import com.runner.academy.service.ActiveWorkoutCheckpoint
import com.runner.academy.service.AutoPauseController
import com.runner.academy.service.GpsLocationProcessor
import com.runner.academy.service.GpsStatusWatchdog
import com.runner.academy.service.HeartRateTracker
import com.runner.academy.service.LivePaceTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import com.runner.academy.service.WorkoutSessionManager
import com.runner.academy.ui.tracking.LiveWorkoutBuilder
import com.runner.academy.data.WorkoutTemplateSegment
import com.runner.academy.util.CalorieProfile
import com.runner.academy.util.HrZones
import com.runner.academy.util.IntervalEngine
import com.runner.academy.util.IntervalSegmentsJson
import com.runner.academy.util.StepDistanceEstimator
import com.runner.academy.util.TrackSanitizer
import com.runner.academy.voice.CoachInput
import com.runner.academy.voice.VoiceCoach
import com.runner.academy.voice.VoiceLine
import com.runner.academy.voice.VoiceSettings

/** How the replayed session is set up. */
data class ReplaySettings(
    /** The auto-pause setting. */
    val autoPause: Boolean = false,
    /** An interval workout (segments set): no auto-pause there, whatever [autoPause] says. */
    val intervals: Boolean = false,
    /** The step sensor is there and allowed; false: fixes and ticks carry no steps. */
    val stepsAvailable: Boolean = true,
    /** The phone has a barometer; false: fixes carry no pressure altitude, whatever the run has. */
    val barometerAvailable: Boolean = true,
    /** The run's frozen stride for bridges, as the service gives the processor. */
    val stepDistance: StepDistanceEstimator? = null,
    val type: WorkoutType = WorkoutType.EASY_RUN,
    /** `userPreferences.calorieProfile()` the timer and the save read. */
    val calorieProfile: CalorieProfile = CalorieProfile(weightKg = 70f, sex = CalorieProfile.Sex.MALE, ageYears = null),
    /** The workout's segments, as the service gets them; an interval workout (no auto-pause). */
    val segments: List<WorkoutTemplateSegment>? = null,
    /** The voice settings the service reads on each tick; on by default on the bench. */
    val voice: VoiceSettings = VoiceSettings.DEFAULT.copy(enabled = true),
    /** `userPreferences.hrZones()` for the target check; null: the zones are off. */
    val zones: HrZones? = null
) {
    val hasSegments: Boolean get() = intervals || !segments.isNullOrEmpty()
}

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

    /** The auto-pause setting switched to [on] at [atSec] mid-run. */
    data class AutoPauseSetting(val atSec: Int, val on: Boolean) : ReplayEvent()
}

data class SessionReplayResult(
    /** What the screen shows at Stop. */
    val elapsedMs: Long,
    val movingMs: Long,
    /** The live distance at Stop. */
    val distanceM: Double,
    val pauses: List<PauseInterval>,
    /** The session's track points at Stop, as the service recorded them (steps included). */
    val livePoints: List<TrackPoint>,
    /** The row the save path builds from the stopped session. */
    val savedWorkout: Workout,
    /** What the voice coach said on the timer: (ms since the start, line). */
    val voice: List<Pair<Long, VoiceLine>> = emptyList(),
    /** The current-pace tile on each tick of the timer: (ms since the start, pace). */
    val livePace: List<Pair<Long, LivePace?>> = emptyList(),
    /** What the segment bar and the target indicator show on each tick: (ms since the start, state). */
    val interval: List<Pair<Long, IntervalLive?>> = emptyList()
)

/**
 * Replays a [SyntheticRun] through the service's session glue on simulated time: the 1 s timer
 * (silence by steps, the [AutoPauseController] tick — the service's own glue, setting changes
 * mid-run included — clock tick), the per-fix branch of
 * `WorkoutTrackingService.updateLocation` into [WorkoutSessionManager] and the detector, the
 * periodic GPS watchdog ([GpsStatusWatchdog], screen on: every 2 s, lost after 3 × 5 s), manual
 * pause / resume (GPS off, steps dropped by the step tracker, the detector reset), a process
 * restart through a Gson round trip of [ActiveWorkoutCheckpoint] (an open auto-pause restores
 * the detector paused), and Stop through the VM's save path ([LiveWorkoutBuilder]).
 *
 * Simulated time is both the wall and the monotonic clock (a fix's time is its
 * `elapsedRealtime`), so the service's mono → wall conversion is the identity here.
 *
 * A run with a heart-rate script ([SyntheticRun.hrAt]) plays it through a [FakeHeartRateSensor]
 * at 1 Hz into the service's [HeartRateTracker]; each fix takes `bpmAt` its own time.
 *
 * The voice: `tickVoice()` after the clock tick feeds the service's [VoiceCoach] (segments from
 * [ReplaySettings.segments]; a kill restores their cursor as the service last saved it, on the
 * tick that changed it; a restore resumes a new coach) and
 * collects its lines in [SessionReplayResult.voice] and the segment's state (the target check
 * included) in [SessionReplayResult.interval]. The speech engine is modelled only as busy: each
 * line said speaks 3 s. Status lines (GPS, sensor, auto-pause) are not modelled.
 *
 * The live pace: `tickLivePace()` before the voice feeds a [LivePaceTracker] on the run's stride
 * ([ReplaySettings.stepDistance]) with the fix age of the watchdog's clock and collects the
 * tile's pace in [SessionReplayResult.livePace]; `paceProbe` sees the tracker after each tick (a
 * window cut at a segment start). The request interval is a fixed 1 s here, so GPS is fresh for
 * 5 s after a fix; the service uses its adaptive interval (`lastAppliedAdaptiveIntervalMs`, 1–3 s
 * on foot), which gives the same 5–6 s.
 *
 * Not modelled: the unreliable-signal latch, the lastKnown pull of the watchdog and the stride
 * learner. Keep in sync with the service when that glue changes, like [GpsReplay.live].
 */
object SessionReplay {

    private const val TICK_MS = 1_000L
    /** The watchdog's period and timeout with the screen on (`WorkoutTrackingService`). */
    private const val WATCHDOG_EVERY_TICKS = 2
    private const val LOST_TIMEOUT_MS = 5_000L
    /** The location request's interval: fixes come once a second on the bench. */
    private const val REQUEST_INTERVAL_MS = 1_000L
    private const val NANOS_PER_MS = 1_000_000L
    /** How long one voice line "speaks" on the bench (architecture §01). */
    private const val SPEECH_MS = 3_000L
    private val gson = GsonBuilder().create()

    fun run(
        run: SyntheticRun,
        settings: ReplaySettings = ReplaySettings(),
        events: List<ReplayEvent> = emptyList(),
        /** After each tick: (ms since the start, the service's live-pace tracker). */
        paceProbe: ((Long, LivePaceTracker) -> Unit)? = null
    ): SessionReplayResult {
        val start = run.startTimeMs
        val stopSec = events.filterIsInstance<ReplayEvent.Stop>().minOfOrNull { it.atSec } ?: run.durationSec
        val pauses = run.manualPauseSeconds +
            events.filterIsInstance<ReplayEvent.ManualPause>().map { it.atSec to it.atSec + it.sec }
        val pauseStarts = pauses.map { it.first }.toSet()
        val pauseEnds = pauses.map { it.second }.toSet()
        val kills = events.filterIsInstance<ReplayEvent.KillAndRestore>().associateBy { it.atSec }
        val settingChanges = events.filterIsInstance<ReplayEvent.AutoPauseSetting>().associateBy { it.atSec }
        val fixes = ArrayDeque(run.rawPoints())

        val live = Live(run, settings, paceProbe)
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
            // The setting is read by the next tick, like the service reads the preference
            settingChanges[sec]?.let { live.autoPauseOn = it.on }
            if (sec in pauseEnds && live.paused) live.resume(now)
            if (sec in pauseStarts && live.tracking) live.pause(now)
            val kill = kills[sec]
            if (kill != null) {
                checkpointJson = live.checkpoint(now)
                killedAtMs = now
                live.kill()
                if (kill.downSec > 0) {
                    deadUntilSec = sec + kill.downSec
                    fixes.dropWhile(now + TICK_MS)
                    continue
                }
                live.restore(checkpointJson!!, now, now)
            }
            // The sensor notifies once a second, pauses included (the service keeps the lease)
            live.heartRateSample(now, sinceStartMs = now - start)
            while (fixes.isNotEmpty() && fixes.first().timestamp < now + TICK_MS) {
                val fix = fixes.removeFirst()
                // Delivered without batching: received in the second of its own time
                if (live.tracking) live.onFix(fix, receivedAt = maxOf(now, fix.timestamp))
            }
        }
        return live.stop(start + stopSec * TICK_MS)
    }

    private fun ArrayDeque<TrackPoint>.dropWhile(beforeMs: Long) {
        while (isNotEmpty() && first().timestamp < beforeMs) removeFirst()
    }

    /** One service process: its session manager, fix processor, auto-pause detector and watchdog. */
    private class Live(
        private val run: SyntheticRun,
        private val settings: ReplaySettings,
        private val paceProbe: ((Long, LivePaceTracker) -> Unit)?
    ) {
        var manager = WorkoutSessionManager()
        var processor = GpsLocationProcessor()
        var autoPause = AutoPauseController()
        /** `isCurrentlyTracking`: running and not paused. */
        var tracking = false
        val paused: Boolean get() = manager.getSession().isPaused
        /** `userPreferences.autoPause`, may change mid-run. */
        var autoPauseOn = settings.autoPause

        /** `serviceIntervalEngine` and `voiceCoach`: a new process restores the first, not the second. */
        private var engine = settings.segments?.takeIf { it.isNotEmpty() }?.let { IntervalEngine(it) }
        /**
         * The cursor as last written to disk: the service saves it when the tick changes it (not
         * with the session's throttled saves), so a kill restores from here, not from the live one.
         */
        private var savedCursor = engine?.snapshot()
        private var coach = VoiceCoach()
        val voice = mutableListOf<Pair<Long, VoiceLine>>()
        /** `livePaceTracker`: one per run (and per restore) on the run's stride. */
        private var livePaceTracker = LivePaceTracker(settings.stepDistance)
        val paces = mutableListOf<Pair<Long, LivePace?>>()
        val intervals = mutableListOf<Pair<Long, IntervalLive?>>()
        /** The speech engine: each line said "speaks" [SPEECH_MS]; the beep is outside it. */
        private var speechUntilMs = 0L

        /** Sensor steps the step tracker never counted (pauses, a dead process). */
        private var droppedSteps = 0
        private var stepsAtPause: Int? = null

        // The watchdog's clocks (`lastLocationTime`, `lastAnyFixTime`) and its 2 s loop
        private var lastGoodFixMs = 0L
        private var lastAnyFixMs = 0L
        private var watchdogTicks = 0

        /**
         * The paired heart-rate sensor ([SyntheticRun.hrAt]) and the service's tracker on it:
         * leased from the start (or a restore) to the stop; a dead process loses the history.
         */
        private val hrSensor = FakeHeartRateSensor(SensorLink.CONNECTED)
        private val hrScope = CoroutineScope(Dispatchers.Unconfined)
        private var hrNowNanos = 0L
        var heartRate: HeartRateTracker? = null
            private set

        private fun startHeartRate() {
            heartRate?.stop()
            if (run.hrAt == null) return
            heartRate = HeartRateTracker(hrSensor, clockNanos = { hrNowNanos }, scope = hrScope).also { it.start() }
        }

        /** The sensor's sample of second [now]. */
        fun heartRateSample(now: Long, sinceStartMs: Long) {
            val script = run.hrAt ?: return
            hrNowNanos = now * NANOS_PER_MS
            hrSensor.emit(script(sinceStartMs), atNanos = now * NANOS_PER_MS)
        }

        /** The process dies: its tracker and lease with it. */
        fun kill() {
            heartRate?.stop()
            heartRate = null
        }

        private fun sensorSteps(timeMs: Long): Pair<Int, Float?>? =
            if (settings.stepsAvailable) run.stepsAt(timeMs) else null

        private fun trackerSteps(timeMs: Long): Pair<Int, Float?>? =
            sensorSteps(timeMs)?.let { (steps, cadence) -> (steps - droppedSteps) to cadence }

        fun start(now: Long) {
            lastGoodFixMs = 0L
            lastAnyFixMs = 0L
            watchdogTicks = 0
            autoPause.reset()
            processor.reset(workoutType = settings.type, stepDistance = settings.stepDistance)
            livePaceTracker = LivePaceTracker(settings.stepDistance)
            startHeartRate()
            manager.startNewSession(initialGpsStatus = GpsStatus.SEARCHING, now = now)
            tracking = true
        }

        /**
         * The 1 s timer: `countSilenceBySteps()`, `tickAutoPause()`, then `tickElapsedTime` and `updateCalories`.
         * The watchdog runs on its own 2 s loop, modelled here on every second tick.
         */
        fun tick(now: Long) {
            if (++watchdogTicks % WATCHDOG_EVERY_TICKS == 0) watchdog(now)
            trackerSteps(now)?.let { (steps, cadence) ->
                val added = processor.countSilence(now, steps, cadence)
                manager.setOpenStepMeters(processor.pendingStepMeters)
                manager.addStepDistance(added)
            }
            tickAutoPause(now)
            manager.tickElapsedTime(broadcast = false, now = now)
            manager.updateCalories(settings.calorieProfile)
            tickLivePace(now)
            tickVoice(now)
        }

        /** `tickLivePace()`: auto-paused, the window starts over; else GPS while its last good fix is fresh. */
        private fun tickLivePace(now: Long) {
            val session = manager.getSession()
            if (session.autoPaused) {
                livePaceTracker.restart(atMovingMs = session.movingTime)
                manager.setLivePace(null)
            } else {
                val fixAgeMs = lastGoodFixMs.takeIf { it > 0L }?.let { now - it }
                val fresh = LivePaceTracker.isGpsFresh(fixAgeMs, REQUEST_INTERVAL_MS, processor.inFalseSignal, session.gpsStatus)
                val steps = trackerSteps(now)
                livePaceTracker.onTick(session.movingTime, session.distance * 1000f, steps?.first, fresh, fixAgeMs ?: 0L)
                manager.setLivePace(livePaceTracker.pace())
            }
            paces += (now - run.startTimeMs) to manager.getSession().livePace
            paceProbe?.invoke(now - run.startTimeMs, livePaceTracker)
        }

        /** `tickVoice()`: the coach on the tick, the tile's heart rate of this second. */
        private fun tickVoice(now: Long) {
            val session = manager.getSession()
            val result = coach.onTick(
                CoachInput(
                    elapsedMs = session.currentTime,
                    movingMs = session.movingTime,
                    distanceM = session.distance * 1000f,
                    autoPaused = session.autoPaused,
                    avgPace = session.avgPace,
                    settings = settings.voice,
                    heartRate = heartRate?.display(now * NANOS_PER_MS),
                    engine = engine,
                    livePace = livePaceTracker,
                    zones = settings.zones.takeIf { engine != null },
                    speechBusy = now < speechUntilMs
                )
            )
            manager.setInterval(result.interval)
            intervals += (now - run.startTimeMs) to result.interval
            result.lines.forEach { line ->
                voice += (now - run.startTimeMs) to line
                if (line != VoiceLine.SegmentStart) speechUntilMs = maxOf(speechUntilMs, now) + SPEECH_MS
            }
            // `maybeSaveCheckpoint(force = true)` on a changed cursor
            engine?.snapshot()?.takeIf { it != savedCursor }?.let { savedCursor = it }
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

        /**
         * `tickAutoPause()`: mono and wall are the same clock here. The voice, the notification
         * and the forced checkpoint of a transition are not modelled: a kill here always saves
         * the live state at that moment.
         */
        private fun tickAutoPause(now: Long) {
            autoPause.tick(
                manager = manager,
                enabled = autoPauseOn && !settings.hasSegments,
                steps = trackerSteps(now)?.first,
                nowMono = now,
                nowWall = now
            )
        }

        /** The tracking branch of `updateLocation`, the fix received at [receivedAt]. */
        fun onFix(fix: TrackPoint, receivedAt: Long) {
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
                cadence = cadence,
                baroM = if (settings.barometerAvailable) fix.baroM else null,
                // `updateLocation`: the value at the fix's own time (elapsedRealtime = its time here)
                heartRate = heartRate?.bpmAt(fix.timestamp * NANOS_PER_MS)
            )
            manager.setOpenStepMeters(processor.pendingStepMeters)
            val good = when (result) {
                is GpsLocationProcessor.ProcessResult.Accepted -> true
                is GpsLocationProcessor.ProcessResult.Rejected -> result.refreshGapClock
            }
            // The watchdog's clocks take the time of receipt, the detector the fix's own time
            lastAnyFixMs = receivedAt
            if (good) lastGoodFixMs = receivedAt
            autoPause.onFix(fix.timestamp, if (location.hasSpeed()) location.speed else null, usable = good)
            when (result) {
                is GpsLocationProcessor.ProcessResult.Accepted -> manager.updateMetricsFromLocation(
                    segmentDistanceMeters = result.distanceDeltaMeters,
                    trackPoints = result.trackPoints,
                    trackDataPoints = result.trackDataPoints,
                    rawTrackDataPoints = result.rawTrackDataPoints,
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
                        addedDistanceMeters = result.distanceDeltaMeters
                    )
                    else -> manager.updateLocationOnly(
                        currentLocation = session.currentLocation ?: location,
                        rawTrackDataPoints = result.rawTrackDataPoints,
                        addedDistanceMeters = result.distanceDeltaMeters
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
            autoPause.reset()
            livePaceTracker.restart(atMovingMs = manager.getSession().movingTime)
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
                intervalSegmentsJson = settings.segments?.let { IntervalSegmentsJson.toJson(it) },
                intervalCursor = savedCursor,
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
            // Not checkpointed: the window starts over
            livePaceTracker = LivePaceTracker(settings.stepDistance).apply { restart(atMovingMs = restored.movingTime) }
            // A new tracker with an empty history; the machine connects again
            startHeartRate()
            manager = WorkoutSessionManager().apply {
                restoreSession(restored, checkpoint.lastUpdateTime)
                setOpenStepMeters(processor.pendingStepMeters)
            }
            // An open auto-pause stays open: the new detector waits for fresh steps or speed
            autoPause = AutoPauseController().apply { reset(autoPaused = restored.autoPaused, restored = true) }
            // `rebuildIntervalEngineFromMetadata()` from the saved segments and cursor, then
            // `prepareVoiceForWorkout(restored)`
            engine = IntervalSegmentsJson.parse(checkpoint.intervalSegmentsJson).takeIf { it.isNotEmpty() }?.let { segments ->
                IntervalEngine(segments).apply { checkpoint.intervalCursor()?.let { restore(it) } }
            }
            coach = VoiceCoach().apply { resume(restored.movingTime, restored.distance * 1000f) }
            lastGoodFixMs = checkpoint.lastLocationTime
            lastAnyFixMs = checkpoint.lastLocationTime
            watchdogTicks = 0
            tracking = !restored.isPaused
        }

        /** Stop: the VM freezes the session at Stop and saves it. */
        fun stop(now: Long): SessionReplayResult {
            heartRate?.stop()
            hrScope.cancel()
            val stopped = LiveWorkoutBuilder.stopped(manager.getSession(), now)
            val workout = LiveWorkoutBuilder.build(
                session = stopped,
                workoutType = settings.type,
                manualDistanceKm = null,
                intervalSegmentsJson = null,
                stepDistance = settings.stepDistance,
                calorieProfile = settings.calorieProfile,
                stopAt = now
            ) ?: error("nothing saved")
            return SessionReplayResult(
                elapsedMs = stopped.currentTime,
                movingMs = stopped.movingTime,
                distanceM = stopped.distance * 1000.0,
                pauses = stopped.clock.pauses,
                livePoints = stopped.trackDataPoints,
                savedWorkout = workout,
                voice = voice.toList(),
                livePace = paces.toList(),
                interval = intervals.toList()
            )
        }
    }
}
