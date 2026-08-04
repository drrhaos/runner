package com.runner.academy.ui.tracking

import android.content.Context
import android.view.View
import com.runner.academy.R
import com.runner.academy.data.WorkoutSession
import com.runner.academy.data.WorkoutTemplateSegment
import com.runner.academy.data.displayName
import com.runner.academy.data.localizedTitle
import com.runner.academy.databinding.FragmentWorkoutTrackingBinding
import com.runner.academy.ui.trainingplan.formatSegmentParams
import com.runner.academy.util.IntervalEngine
import com.runner.academy.util.IntervalSegmentsJson

/**
 * Owns IntervalEngine lifecycle and interval panel UI.
 * Voice cues for intervals live in [com.runner.academy.service.WorkoutTrackingService].
 */
class IntervalTrackingController(
    private val context: Context,
    private val binding: FragmentWorkoutTrackingBinding,
    private val viewModel: WorkoutTrackingViewModel,
    private val selectedMode: () -> TrackingWorkoutMode
) {
    private var intervalEngine: IntervalEngine? = null
    var activeScheduledId: Long? = null
        private set

    fun hasEngine(): Boolean = intervalEngine != null

    fun allSegments(): List<WorkoutTemplateSegment>? = intervalEngine?.allSegments()

    fun segmentsJsonForSave(): String? {
        return (
            intervalEngine?.allSegments()
                ?: viewModel.activeIntervalSegments.value
            )?.let { IntervalSegmentsJson.toJson(it) }
    }

    fun updatePreview() {
        val session = viewModel.workoutSession.value
        if (session.isTracking || session.isPaused) return
        val mode = selectedMode()
        val segments = mode.segments()
        if (mode.hasIntervals()) {
            binding.layoutIntervalPanel.visibility = View.VISIBLE
            binding.progressIntervalSegments.setSegments(segments)
            binding.progressIntervalSegments.setProgress(0, 0f)
            val first = segments.firstOrNull()
            val title = mode.displayTitle()
            val firstTitle = first?.localizedTitle(context)
            binding.textViewIntervalTitle.text = if (firstTitle != null && title.isNotEmpty()) {
                "$title · $firstTitle"
            } else {
                firstTitle ?: title
            }
            if (first != null) {
                binding.textViewIntervalParams.visibility = View.VISIBLE
                binding.textViewIntervalParams.text = formatSegmentParams(context, first)
                binding.textViewIntervalProgressLabel.text = context.getString(
                    R.string.tracking_interval_status,
                    1,
                    segments.size,
                    first.kind.displayName(context),
                    0
                )
            } else {
                binding.textViewIntervalParams.visibility = View.GONE
                binding.textViewIntervalProgressLabel.text =
                    context.getString(R.string.tracking_interval_progress, 1, segments.size)
            }
        } else {
            binding.layoutIntervalPanel.visibility = View.GONE
        }
    }

    fun maybeStartForActiveSession() {
        val session = viewModel.workoutSession.value
        if (!(session.isTracking || session.isPaused)) return
        if (intervalEngine != null) return
        val segments = resolveActiveIntervalSegments() ?: return
        startEngine(segments, restoreCursor = true)
        update(session)
    }

    fun prepareForNewWorkout() {
        if (selectedMode().hasIntervals()) {
            startEngine(restoreCursor = false)
        } else {
            clear()
        }
    }

    fun clear() {
        intervalEngine = null
        activeScheduledId = null
        viewModel.clearActiveIntervalSegments()
        if (!selectedMode().hasIntervals()) {
            binding.layoutIntervalPanel.visibility = View.GONE
        }
    }

    fun update(session: WorkoutSession) {
        val engine = intervalEngine ?: return
        val distanceMeters = session.distance * 1000f
        val state = engine.update(session.currentTime, distanceMeters)
        val segment = state.segment
        binding.layoutIntervalPanel.visibility = View.VISIBLE
        binding.progressIntervalSegments.setProgress(state.segmentIndex, state.segmentProgress)

        val percent = (state.segmentProgress * 100f).toInt().coerceIn(0, 100)
        binding.textViewIntervalTitle.text = segment?.localizedTitle(context)
            ?: selectedMode().displayTitle()
        if (segment != null) {
            binding.textViewIntervalParams.visibility = View.VISIBLE
            binding.textViewIntervalParams.text = formatSegmentParams(context, segment)
            binding.textViewIntervalProgressLabel.text = context.getString(
                R.string.tracking_interval_status,
                (state.segmentIndex + 1).coerceAtMost(engine.segmentCount()),
                engine.segmentCount(),
                segment.kind.displayName(context),
                percent
            )
        } else {
            binding.textViewIntervalParams.visibility = View.GONE
            binding.textViewIntervalProgressLabel.text = context.getString(
                R.string.tracking_interval_progress,
                (state.segmentIndex + 1).coerceAtMost(engine.segmentCount()),
                engine.segmentCount()
            )
        }
    }

    fun onExpandedInfoChanged(expanded: Boolean) {
        if (!expanded && intervalEngine == null &&
            viewModel.activeIntervalSegments.value.isNullOrEmpty()
        ) {
            binding.layoutIntervalPanel.visibility = View.GONE
        } else if (expanded) {
            updatePreview()
        }
    }

    private fun resolveActiveIntervalSegments(): List<WorkoutTemplateSegment>? {
        viewModel.activeIntervalSegments.value?.takeIf { it.isNotEmpty() }?.let { return it }
        val mode = selectedMode()
        return mode.segments().takeIf { mode.hasIntervals() && it.isNotEmpty() }
    }

    private fun startEngine(
        segments: List<WorkoutTemplateSegment>? = null,
        restoreCursor: Boolean = false
    ) {
        val mode = selectedMode()
        val plan = segments?.takeIf { it.isNotEmpty() }
            ?: mode.segments().takeIf { it.isNotEmpty() }
        if (plan.isNullOrEmpty()) {
            intervalEngine = null
            activeScheduledId = null
            viewModel.clearActiveIntervalSegments()
            return
        }
        viewModel.setActiveIntervalSegments(plan)
        val engine = IntervalEngine(plan)
        if (restoreCursor) {
            viewModel.getIntervalCursor()?.let { engine.restore(it) }
        }
        intervalEngine = engine
        activeScheduledId = mode.scheduledIdOrNull()
        binding.layoutIntervalPanel.visibility = View.VISIBLE
        binding.progressIntervalSegments.setSegments(plan)
        binding.progressIntervalSegments.setProgress(0, 0f)
    }
}
