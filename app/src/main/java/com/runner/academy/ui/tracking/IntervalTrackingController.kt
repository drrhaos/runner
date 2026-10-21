package com.runner.academy.ui.tracking

import android.content.Context
import android.content.res.ColorStateList
import android.view.View
import android.view.accessibility.AccessibilityManager
import androidx.core.content.ContextCompat
import androidx.core.widget.TextViewCompat
import com.google.android.material.color.MaterialColors
import com.runner.academy.R
import com.runner.academy.data.IntervalLive
import com.runner.academy.data.SegmentTarget
import com.runner.academy.data.WorkoutSession
import com.runner.academy.data.WorkoutTemplateSegment
import com.runner.academy.data.displayName
import com.runner.academy.data.localizedTitle
import com.runner.academy.data.target
import com.runner.academy.databinding.FragmentWorkoutTrackingBinding
import com.runner.academy.ui.trainingplan.formatSegmentParams
import com.runner.academy.util.IntervalSegmentsJson
import com.runner.academy.voice.TargetState

/**
 * The interval panel: the plan of the selected mode before the start (a preview), then the
 * running workout's segments as the service reports them ([WorkoutSession.interval]). The
 * service's engine is the only one of a workout, so the bar, the voice and the target indicator
 * never disagree; the screen keeps no engine of its own.
 *
 * The target's verdict goes to the bar's circle and the verdict line; with TalkBack on, a hint
 * the voice did not say (voice or its target cues off) is announced ([TargetAnnouncer]).
 */
class IntervalTrackingController(
    private val context: Context,
    private val binding: FragmentWorkoutTrackingBinding,
    private val viewModel: WorkoutTrackingViewModel,
    private val selectedMode: () -> TrackingWorkoutMode,
    private val talkBackOn: () -> Boolean = {
        (context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager)?.isTouchExplorationEnabled == true
    },
    private val announce: (View, String) -> Unit = { view, text -> view.announceForAccessibility(text) }
) {
    /** The running workout's segments; null before the start and after the save. */
    private var segments: List<WorkoutTemplateSegment>? = null
    private val announcer = TargetAnnouncer()
    private var shownTarget: TargetIndicatorLook? = null

    /** What [shownTarget] was built from. */
    private data class LookKey(val index: Int, val completed: Boolean, val target: TargetState, val zonesOn: Boolean)
    private var lookKey: LookKey? = null

    private fun resetTarget() {
        lookKey = null
        showTarget(null)
    }

    var activeScheduledId: Long? = null
        private set

    /** The segments to save with the workout. */
    fun segmentsJsonForSave(): String? = viewModel.activeIntervalSegments.value?.let { IntervalSegmentsJson.toJson(it) }

    fun updatePreview() {
        val session = viewModel.workoutSession.value
        if (session.isTracking || session.isPaused) return
        val mode = selectedMode()
        val segments = mode.segments()
        if (mode.hasIntervals()) {
            binding.layoutIntervalPanel.visibility = View.VISIBLE
            binding.progressIntervalSegments.setSegments(segments)
            binding.progressIntervalSegments.setProgress(0, 0f)
            resetTarget()
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
                binding.textViewIntervalParams.contentDescription = formatSegmentParams(context, first, forA11y = true)
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
        if (segments != null) return
        val plan = resolveActiveIntervalSegments() ?: return
        start(plan)
        if (session.interval == null) {
            // Restored and not ticked yet: the service's checkpointed segment, not the first
            viewModel.getIntervalCursor()?.let { binding.progressIntervalSegments.setProgress(it.segmentIndex.coerceIn(0, plan.lastIndex), 0f) }
        }
        update(session)
    }

    fun prepareForNewWorkout() {
        val mode = selectedMode()
        val plan = mode.segments().takeIf { mode.hasIntervals() && it.isNotEmpty() }
        if (plan != null) start(plan) else clear()
    }

    fun clear() {
        segments = null
        activeScheduledId = null
        viewModel.clearActiveIntervalSegments()
        if (!selectedMode().hasIntervals()) {
            binding.layoutIntervalPanel.visibility = View.GONE
        }
    }

    /**
     * Draws the service's segment; before its first tick (just started or restored) the panel
     * keeps what [start] drew.
     */
    fun update(session: WorkoutSession) {
        val plan = segments ?: return
        val live = session.interval ?: return
        val index = live.segmentIndex.coerceIn(0, plan.lastIndex)
        val segment = plan[index]
        binding.layoutIntervalPanel.visibility = View.VISIBLE
        binding.progressIntervalSegments.setProgress(index, live.segmentProgress)

        val percent = (live.segmentProgress * 100f).toInt().coerceIn(0, 100)
        binding.textViewIntervalTitle.text = segment.localizedTitle(context)
        binding.textViewIntervalParams.visibility = View.VISIBLE
        binding.textViewIntervalParams.text = formatSegmentParams(context, segment)
        binding.textViewIntervalParams.contentDescription = formatSegmentParams(context, segment, forA11y = true)
        binding.textViewIntervalProgressLabel.text = context.getString(
            R.string.tracking_interval_status,
            index + 1,
            plan.size,
            segment.kind.displayName(context),
            percent
        )

        // The session comes with every fix too: the look is built again only when its inputs change
        val key = LookKey(index, live.completed, live.target, zonesOn = viewModel.hrZones.value != null)
        if (key != lookKey) {
            lookKey = key
            val target = if (live.completed) SegmentTarget.None else segment.target
            showTarget(TargetIndicatorLook.of(live.target, target, key.zonesOn, context))
        }
        announce(live, shownTarget)
    }

    fun onExpandedInfoChanged(expanded: Boolean) {
        if (!expanded && segments == null &&
            viewModel.activeIntervalSegments.value.isNullOrEmpty()
        ) {
            binding.layoutIntervalPanel.visibility = View.GONE
        } else if (expanded) {
            updatePreview()
        }
    }

    private fun announce(live: IntervalLive, look: TargetIndicatorLook?) {
        // The cheap checks first: the accessibility service is asked only for a hint to announce
        if (look == null || !live.hintDue || live.hintVoiced) return
        if (!announcer.shouldAnnounce(live, talkBackOn())) return
        announce(binding.progressIntervalSegments, context.getString(R.string.target_announce, look.description))
    }

    /** The verdict line and the bar's circle; both only when they change. */
    private fun showTarget(look: TargetIndicatorLook?) {
        binding.progressIntervalSegments.setTarget(look)
        if (look == shownTarget) return
        shownTarget = look
        val line = binding.textViewIntervalTarget
        if (look == null) {
            line.visibility = View.GONE
            return
        }
        val color = when (look.tone) {
            TargetIndicatorLook.Tone.IN -> ContextCompat.getColor(context, R.color.target_in)
            TargetIndicatorLook.Tone.OFF -> ContextCompat.getColor(context, R.color.target_off)
            TargetIndicatorLook.Tone.NEUTRAL -> MaterialColors.getColor(line, com.google.android.material.R.attr.colorOnSurfaceVariant)
        }
        line.text = look.text
        line.setTextColor(color)
        val size = context.resources.getDimensionPixelSize(R.dimen.target_line_icon_size)
        val icon = ContextCompat.getDrawable(context, look.icon)?.mutate()?.apply { setBounds(0, 0, size, size) }
        line.setCompoundDrawablesRelative(icon, null, null, null)
        TextViewCompat.setCompoundDrawableTintList(line, ColorStateList.valueOf(color))
        line.visibility = View.VISIBLE
    }

    private fun resolveActiveIntervalSegments(): List<WorkoutTemplateSegment>? {
        viewModel.activeIntervalSegments.value?.takeIf { it.isNotEmpty() }?.let { return it }
        val mode = selectedMode()
        return mode.segments().takeIf { mode.hasIntervals() && it.isNotEmpty() }
    }

    /** The bar of a running workout at its first segment until the service's first tick. */
    private fun start(plan: List<WorkoutTemplateSegment>) {
        viewModel.setActiveIntervalSegments(plan)
        segments = plan
        activeScheduledId = selectedMode().scheduledIdOrNull()
        binding.layoutIntervalPanel.visibility = View.VISIBLE
        binding.progressIntervalSegments.setSegments(plan)
        binding.progressIntervalSegments.setProgress(0, 0f)
        resetTarget()
    }
}
