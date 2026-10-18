package com.runner.academy.ui.tracking

import android.content.Context
import com.runner.academy.data.ScheduledWorkoutStatus
import com.runner.academy.data.ScheduledWorkoutWithTemplate
import com.runner.academy.data.TrainingPlanRepository
import com.runner.academy.data.WorkoutTemplateWithSegments
import com.runner.academy.data.WorkoutType
import com.runner.academy.databinding.FragmentWorkoutTrackingBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Owns the tracking modes (easy run, today's plan, templates), today's plan pick, and selection
 * sync with the ViewModel; the button and its sheet are drawn by [WorkoutModePicker].
 */
class TrackingModeController(
    context: Context,
    private val binding: FragmentWorkoutTrackingBinding,
    private val viewModel: WorkoutTrackingViewModel,
    private val planRepository: TrainingPlanRepository,
    private val scope: CoroutineScope,
    private val onModeApplied: () -> Unit
) {
    var selectedMode: TrackingWorkoutMode = TrackingWorkoutMode.EasyRun
        private set
    var selectedWorkoutType: WorkoutType = WorkoutType.EASY_RUN
        private set
    var todaysScheduled: ScheduledWorkoutWithTemplate? = null
        private set

    private val picker = WorkoutModePicker(context, binding)
    private var modeOptions: List<TrackingWorkoutMode> = listOf(TrackingWorkoutMode.EasyRun)
    private var activePlanName: String? = null

    fun setupButton() {
        picker.showSelected(selectedMode)
        binding.buttonWorkoutMode.setOnClickListener {
            picker.open(modeOptions, selectedMode, activePlanName) { applySelectedMode(it) }
        }
    }

    fun loadModes() {
        scope.launch {
            todaysScheduled = planRepository.getTodaysScheduledWorkout()
            activePlanName = planRepository.getActivePlan()?.name
            val templates = planRepository.getAllTemplatesWithSegments()
            rebuildModeOptions(templates)
            onModeApplied()
        }
    }

    fun syncFromViewModel() {
        if (modeOptions.isEmpty()) return
        val preferred = modeOptions[resolvePreferredModeIndex(modeOptions)]
        if (preferred == selectedMode) return
        applySelectedMode(preferred, persistSelection = false)
    }

    fun refreshTodaysScheduled() {
        scope.launch {
            todaysScheduled = planRepository.getTodaysScheduledWorkout()
        }
    }

    /** The view is going away: an open sheet must not outlive it. */
    fun release() = picker.dismiss()

    private fun hasFollowablePlan(): Boolean {
        val today = todaysScheduled ?: return false
        return today.scheduled.status == ScheduledWorkoutStatus.PLANNED &&
            today.template != null &&
            today.segments.isNotEmpty()
    }

    private fun rebuildModeOptions(templates: List<WorkoutTemplateWithSegments>) {
        val options = mutableListOf<TrackingWorkoutMode>(TrackingWorkoutMode.EasyRun)
        if (hasFollowablePlan()) {
            todaysScheduled?.let { options.add(TrackingWorkoutMode.PlanToday(it)) }
        }
        templates.forEach { options.add(TrackingWorkoutMode.Template(it)) }
        modeOptions = options
        applySelectedMode(options[resolvePreferredModeIndex(options)], persistSelection = false)
    }

    private fun resolvePreferredModeIndex(options: List<TrackingWorkoutMode>): Int {
        val selection = viewModel.modeSelection.value
        val index = when (selection) {
            null -> {
                if (hasFollowablePlan()) {
                    options.indexOfFirst { it is TrackingWorkoutMode.PlanToday }
                } else {
                    0
                }
            }
            TrackingModeSelection.EasyRun ->
                options.indexOfFirst { it is TrackingWorkoutMode.EasyRun }
            TrackingModeSelection.PlanToday ->
                options.indexOfFirst { it is TrackingWorkoutMode.PlanToday }
                    .takeIf { it >= 0 }
                    ?: 0
            is TrackingModeSelection.Template ->
                options.indexOfFirst {
                    it is TrackingWorkoutMode.Template &&
                        it.data.template.id == selection.templateId
                }.takeIf { it >= 0 }
                    ?: if (hasFollowablePlan()) {
                        options.indexOfFirst { it is TrackingWorkoutMode.PlanToday }
                    } else {
                        0
                    }
        }
        return index.coerceIn(0, options.lastIndex.coerceAtLeast(0))
    }

    private fun applySelectedMode(mode: TrackingWorkoutMode, persistSelection: Boolean = true) {
        selectedMode = mode
        selectedWorkoutType = mode.workoutType()
        picker.showSelected(mode)
        if (persistSelection) {
            viewModel.setModeSelection(mode.toSelectionKey())
        } else if (viewModel.modeSelection.value == null) {
            viewModel.setModeSelection(mode.toSelectionKey())
        }
        onModeApplied()
    }
}
