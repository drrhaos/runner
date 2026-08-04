package com.runner.academy.ui.tracking

import android.content.Context
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import com.runner.academy.R
import com.runner.academy.data.ScheduledWorkoutStatus
import com.runner.academy.data.ScheduledWorkoutWithTemplate
import com.runner.academy.data.TrainingPlanRepository
import com.runner.academy.data.WorkoutTemplateWithSegments
import com.runner.academy.data.WorkoutType
import com.runner.academy.databinding.FragmentWorkoutTrackingBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Owns tracking-mode spinner options, today's plan pick, and selection sync with the ViewModel.
 */
class TrackingModeController(
    private val context: Context,
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

    private var modeOptions: List<TrackingWorkoutMode> = listOf(TrackingWorkoutMode.EasyRun)
    private var suppressModeSelectionCallback = false

    fun setupSpinner() {
        binding.spinnerWorkoutType.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    if (suppressModeSelectionCallback) return
                    val mode = modeOptions.getOrNull(position) ?: return
                    applySelectedMode(mode)
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
    }

    fun loadModes() {
        scope.launch {
            todaysScheduled = planRepository.getTodaysScheduledWorkout()
            val templates = planRepository.getAllTemplatesWithSegments()
            rebuildModeOptions(templates)
            onModeApplied()
        }
    }

    fun syncFromViewModel() {
        if (modeOptions.isEmpty()) return
        val preferred = resolvePreferredModeIndex(modeOptions)
        val current = binding.spinnerWorkoutType.selectedItemPosition
        if (preferred == current && selectedMode == modeOptions.getOrNull(preferred)) return
        suppressModeSelectionCallback = true
        binding.spinnerWorkoutType.setSelection(preferred, false)
        suppressModeSelectionCallback = false
        applySelectedMode(modeOptions[preferred], persistSelection = false)
    }

    fun refreshTodaysScheduled() {
        scope.launch {
            todaysScheduled = planRepository.getTodaysScheduledWorkout()
        }
    }

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

        val labels = options.map { mode ->
            when (mode) {
                TrackingWorkoutMode.EasyRun ->
                    context.getString(R.string.workout_type_easy_run)
                is TrackingWorkoutMode.PlanToday ->
                    context.getString(
                        R.string.tracking_mode_plan,
                        mode.scheduled.template?.name.orEmpty()
                    )
                is TrackingWorkoutMode.Template ->
                    mode.data.template.name
            }
        }
        val adapter = ArrayAdapter(
            context,
            android.R.layout.simple_spinner_item,
            labels
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)

        val preferred = resolvePreferredModeIndex(options)
        suppressModeSelectionCallback = true
        binding.spinnerWorkoutType.adapter = adapter
        binding.spinnerWorkoutType.setSelection(preferred, false)
        suppressModeSelectionCallback = false
        applySelectedMode(options[preferred], persistSelection = false)
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
        if (persistSelection) {
            viewModel.setModeSelection(mode.toSelectionKey())
        } else if (viewModel.modeSelection.value == null) {
            viewModel.setModeSelection(mode.toSelectionKey())
        }
        onModeApplied()
    }
}
