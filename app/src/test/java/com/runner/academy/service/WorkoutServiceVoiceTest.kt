package com.runner.academy.service

import android.content.Intent
import com.runner.academy.data.SegmentGoalType
import com.runner.academy.data.SegmentKind
import com.runner.academy.data.WorkoutTemplateSegment
import com.runner.academy.sensor.FakeHeartRateSensor
import com.runner.academy.sensor.SensorLink
import com.runner.academy.util.IntervalSegmentsJson
import com.runner.academy.util.UserPreferences
import com.runner.academy.voice.FakeVoiceOutput
import com.runner.academy.voice.SummaryEvery
import com.runner.academy.voice.SummaryMetric
import com.runner.academy.voice.SummaryPart
import com.runner.academy.voice.VoiceLine
import com.runner.academy.voice.VoiceSettings
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

/**
 * The voice on the workout timer, with a [FakeVoiceOutput] and the service's own clock: the
 * segments and the summaries come on the 1 s tick without any fix, and a manual pause stops them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorkoutServiceVoiceTest {

    private val app = RuntimeEnvironment.getApplication()
    private val prefs = UserPreferences(app)
    private var nowMs = 1_700_000_000_000L
    private val startMs = nowMs
    private var outputs = mutableListOf<FakeVoiceOutput>()
    private val controllers = mutableListOf<ServiceController<WorkoutTrackingService>>()

    /** (second since the start on the wall clock, line). */
    private val said = mutableListOf<Pair<Int, VoiceLine>>()

    private fun service(): WorkoutTrackingService {
        val controller = Robolectric.buildService(WorkoutTrackingService::class.java)
        controllers += controller
        val service = controller.get()
        service.heartRateSource = HeartRateSource(FakeHeartRateSensor(SensorLink.IDLE), MutableStateFlow(emptyMap())) { true }
        service.sessionClockMs = { nowMs }
        service.voiceOutputFactory = {
            FakeVoiceOutput { line -> said += ((nowMs - startMs) / 1_000L).toInt() to line }.also { outputs += it }
        }
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

    /**
     * The process dies without `onDestroy` (no last save): a new service restores from what is on
     * disk once [saved] holds for it (the service writes the checkpoint on a background thread).
     */
    private fun killAndRestore(saved: (ActiveWorkoutCheckpoint) -> Boolean): WorkoutTrackingService {
        val store = ActiveWorkoutStore(app)
        for (attempt in 1..100) {
            if (store.load()?.let(saved) == true) break
            Thread.sleep(20)
        }
        val restored = service()
        restored.onStartCommand(
            Intent(app, WorkoutTrackingService::class.java).setAction(WorkoutTrackingService.ACTION_RESTORE_WORKOUT), 0, 1
        )
        return restored
    }

    private fun timed(sortOrder: Int, sec: Int, kind: SegmentKind) = WorkoutTemplateSegment(
        templateId = 1L, sortOrder = sortOrder, kind = kind, title = "S$sortOrder",
        goalType = SegmentGoalType.DURATION, durationMs = sec * 1_000L
    )

    private val segments = listOf(timed(0, 120, SegmentKind.WORK), timed(1, 60, SegmentKind.RECOVERY))

    private fun WorkoutTrackingService.startIntervals() {
        onStartCommand(
            Intent(app, WorkoutTrackingService::class.java)
                .setAction(WorkoutTrackingService.ACTION_START_WORKOUT)
                .putExtra(WorkoutTrackingService.EXTRA_INTERVAL_SEGMENTS_JSON, IntervalSegmentsJson.toJson(segments)),
            0, 1
        )
    }

    /** The timer's ticks through second [untilSec] of the wall clock. */
    private fun WorkoutTrackingService.tickUntil(untilSec: Int) {
        while ((nowMs - startMs) / 1_000L < untilSec) {
            nowMs += 1_000L
            tickWorkout()
        }
    }

    private fun segmentLines() = said.filter { it.second !is VoiceLine.Status }

    @Test
    fun `a 2 min segment with no fix at all - warning at 90 s, beep at 120 s on the tick`() {
        prefs.voiceFeedback = true
        val service = service()
        service.startIntervals()

        service.tickUntil(200)

        assertEquals(
            listOf(1 to "beep", 90 to "next S1", 120 to "beep"),
            segmentLines().map { (sec, line) ->
                sec to when (line) {
                    is VoiceLine.SegmentUpcoming -> "next ${line.segment.title}"
                    else -> "beep".takeIf { line == VoiceLine.SegmentStart } ?: line.toString()
                }
            }
        )
    }

    @Test
    fun `a manual pause of 2 min stops the segments and the voice`() {
        prefs.voiceFeedback = true
        val service = service()
        service.startIntervals()
        service.tickUntil(60)
        service.pauseWorkout()
        nowMs += 120_000L
        service.resumeWorkout()

        service.tickUntil(250)

        // The warning and the beep moved by the 2 minutes of the pause
        assertEquals(listOf(1, 210, 240), segmentLines().map { it.first })
    }

    @Test
    fun `a kill after the warning with no fix at all - the restore neither warns nor beeps again`() {
        prefs.voiceFeedback = true
        val first = service()
        first.startIntervals()
        first.tickUntil(100)

        // Saved on the tick that consumed the warning: no fix ever came to save it
        val restored = killAndRestore { it.intervalCursor()?.lastUpcomingWarnedIndex == 0 }
        said.clear()
        restored.tickUntil(130)

        assertEquals(listOf(120 to "beep"), segmentLines().map { (sec, line) -> sec to describeSegment(line) })
    }

    @Test
    fun `a restore mid-run resumes the summaries from the restored position on the session clock`() {
        prefs.setVoiceSettings(
            VoiceSettings.DEFAULT.copy(
                enabled = true,
                free = VoiceSettings.DEFAULT.free.copy(
                    summary = SummaryEvery.MIN_1,
                    metrics = setOf(SummaryMetric.DISTANCE, SummaryMetric.TIME)
                )
            )
        )
        val first = service()
        first.startWorkout()
        first.tickUntil(1_020)
        // The run so far covered 3.4 km (by fixes this test does not replay)
        ActiveWorkoutStore(app).save(
            ActiveWorkoutCheckpoint.fromSession(
                first.getCurrentSession().copy(distance = 3.4f), com.runner.academy.data.WorkoutType.EASY_RUN,
                null, null, null, lastLocationTime = 0L, lastUpdateTime = 0L
            )
        )
        val restored = killAndRestore { it.distance == 3.4f }
        said.clear()

        restored.tickUntil(1_100)

        // Nothing for the past: the next minute mark after 17 min of moving, with the restored distance
        assertEquals(
            listOf(1_080 to VoiceLine.Summary(listOf(SummaryPart.Distance(3_400f), SummaryPart.Time(1_080_000L)))),
            segmentLines()
        )
    }

    private fun describeSegment(line: VoiceLine) = when (line) {
        is VoiceLine.SegmentUpcoming -> "next ${line.segment.title}"
        else -> "beep".takeIf { line == VoiceLine.SegmentStart } ?: line.toString()
    }

    @Test
    fun `voice off - no voice made, the segments advance all the same`() {
        val service = service()
        service.startIntervals()

        service.tickUntil(130)

        assertEquals(emptyList<FakeVoiceOutput>(), outputs)
        assertEquals(1, service.getIntervalCursor()?.segmentIndex)
    }

    @Test
    fun `a free run speaks its time summary on the tick without fixes, a switch off mid-run releases the voice`() {
        prefs.setVoiceSettings(
            VoiceSettings.DEFAULT.copy(
                enabled = true,
                free = VoiceSettings.DEFAULT.free.copy(summary = SummaryEvery.MIN_1, metrics = setOf(SummaryMetric.TIME))
            )
        )
        val service = service()
        service.startWorkout()

        service.tickUntil(130)
        assertEquals(
            listOf(60 to VoiceLine.Summary(listOf(SummaryPart.Time(60_000L))), 120 to VoiceLine.Summary(listOf(SummaryPart.Time(120_000L)))),
            segmentLines()
        )

        prefs.voiceFeedback = false
        service.tickUntil(131)
        assertTrue(outputs.single().released)
    }
}
