package com.runner.academy.ui.tracking

import android.app.Application
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import androidx.core.view.ViewCompat
import com.runner.academy.R
import com.runner.academy.data.IntervalLive
import com.runner.academy.data.SegmentGoalType
import com.runner.academy.data.SegmentKind
import com.runner.academy.data.WorkoutDatabase
import com.runner.academy.data.WorkoutRepository
import com.runner.academy.data.WorkoutSession
import com.runner.academy.data.WorkoutTemplate
import com.runner.academy.data.WorkoutTemplateSegment
import com.runner.academy.data.WorkoutTemplateWithSegments
import com.runner.academy.data.displayName
import com.runner.academy.databinding.FragmentWorkoutTrackingBinding
import com.runner.academy.sensor.FakeHeartRateSensor
import com.runner.academy.sensor.SensorLink
import com.runner.academy.util.IntervalSegmentsJson
import com.runner.academy.voice.TargetState
import com.runner.academy.voice.TargetVerdict
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The interval panel draws the running workout from the service's [WorkoutSession.interval]
 * (architecture §04): the segment, its progress and the target's verdict, with no engine of its own.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IntervalTrackingControllerTest {

    private val app = RuntimeEnvironment.getApplication()
    private val context = ContextThemeWrapper(app, R.style.Theme_Runner)
    private val database = WorkoutDatabase.getInMemoryDatabase(app)
    private val viewModel = WorkoutTrackingViewModel(
        WorkoutRepository(database), app as Application, MutableStateFlow(emptyMap()), FakeHeartRateSensor(SensorLink.IDLE),
        hrZonesSource = { null }
    )
    private val binding = FragmentWorkoutTrackingBinding.inflate(LayoutInflater.from(context))

    private fun segment(sortOrder: Int, kind: SegmentKind, pace: Float?) = WorkoutTemplateSegment(
        templateId = 1L, sortOrder = sortOrder, kind = kind, title = "S$sortOrder",
        goalType = SegmentGoalType.DISTANCE, distanceMeters = 400f, targetPaceMinPerKm = pace
    )

    private val segments = listOf(
        segment(0, SegmentKind.WARMUP, null),
        segment(1, SegmentKind.WORK, 4f),
        segment(2, SegmentKind.RECOVERY, null)
    )
    private val mode = TrackingWorkoutMode.Template(WorkoutTemplateWithSegments(WorkoutTemplate(id = 1L, name = "6x400"), segments))
    private var talkBack = false
    private val announced = mutableListOf<String>()
    private val controller = IntervalTrackingController(
        context, binding, viewModel, { mode }, talkBackOn = { talkBack }, announce = { _, text -> announced += text }
    )

    @After
    fun tearDown() = database.close()

    private fun live(index: Int, progress: Float, target: TargetState, due: Boolean = false, voiced: Boolean = false) =
        WorkoutSession(isTracking = true).copy(
            interval = IntervalLive(index, progress, 10_000L, completed = false, target = target, hintDue = due, hintVoiced = voiced)
        )

    @Test
    fun `the bar, the title and the status follow the service's segment`() {
        controller.prepareForNewWorkout()
        assertEquals(segments, viewModel.activeIntervalSegments.value)
        assertEquals(IntervalSegmentsJson.toJson(segments), controller.segmentsJsonForSave())

        controller.update(live(1, 0.5f, TargetState(TargetVerdict.PENDING)))

        assertEquals(View.VISIBLE, binding.layoutIntervalPanel.visibility)
        assertEquals(
            context.getString(R.string.tracking_interval_status, 2, 3, SegmentKind.WORK.displayName(context), 50),
            binding.textViewIntervalProgressLabel.text.toString()
        )
        assertEquals("S1", binding.textViewIntervalTitle.text.toString())
    }

    @Test
    fun `TalkBack announces a due hint the voice did not say, once`() {
        talkBack = true
        controller.prepareForNewWorkout()
        val slow = TargetState(TargetVerdict.TOO_SLOW, value = 4.25f)

        controller.update(live(1, 0.3f, slow, due = true, voiced = true))
        assertEquals(emptyList<String>(), announced)

        val due = live(1, 0.4f, slow, due = true)
        controller.update(due)
        controller.update(due)
        assertEquals(listOf("Segment target: Speed up, 4 minutes 15 seconds per kilometer"), announced)
    }

    @Test
    fun `the verdict line and the bar's state - shown with a target, gone without`() {
        controller.prepareForNewWorkout()

        controller.update(live(1, 0.3f, TargetState(TargetVerdict.TOO_SLOW, value = 4.25f)))
        assertEquals(View.VISIBLE, binding.textViewIntervalTarget.visibility)
        assertEquals("Speed up · 4:15", binding.textViewIntervalTarget.text.toString())
        assertEquals("Speed up, 4 minutes 15 seconds per kilometer", ViewCompat.getStateDescription(binding.progressIntervalSegments))
        assertEquals(TargetIndicatorLook.Tone.OFF, binding.progressIntervalSegments.target?.tone)

        controller.update(live(2, 0.1f, TargetState.NONE))
        assertEquals(View.GONE, binding.textViewIntervalTarget.visibility)
        assertNull(ViewCompat.getStateDescription(binding.progressIntervalSegments))
        assertNull(binding.progressIntervalSegments.target)
    }

    @Test
    fun `no tick yet - the panel keeps the start`() {
        controller.prepareForNewWorkout()
        controller.update(WorkoutSession(isTracking = true))
        assertEquals(View.GONE, binding.textViewIntervalTarget.visibility)
        assertEquals(View.VISIBLE, binding.layoutIntervalPanel.visibility)
    }

    @Test
    fun `zones off - a zone target without data says so`() {
        val zoneMode = TrackingWorkoutMode.Template(
            WorkoutTemplateWithSegments(WorkoutTemplate(id = 2L, name = "Z"), listOf(segments[1].copy(targetPaceMinPerKm = null, targetHrZone = 4)))
        )
        val zoneController = IntervalTrackingController(context, binding, viewModel, { zoneMode }, talkBackOn = { false })
        zoneController.prepareForNewWorkout()

        zoneController.update(live(0, 0.5f, TargetState(TargetVerdict.NO_DATA)))

        assertEquals("Zones are off", binding.textViewIntervalTarget.text.toString())
    }
}
