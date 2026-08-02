package com.runner.academy.ui.trainingplan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.runner.academy.data.SegmentGoalType
import com.runner.academy.data.SegmentKind
import com.runner.academy.data.TrainingIcon
import com.runner.academy.data.TrainingPlan
import com.runner.academy.data.TrainingPlanDay
import com.runner.academy.data.TrainingPlanRepository
import com.runner.academy.data.WorkoutTemplate
import com.runner.academy.data.WorkoutTemplateSegment
import com.runner.academy.data.WorkoutTemplateWithSegments
import com.runner.academy.data.WorkoutType
import com.runner.academy.util.TrainingPlanBackupFormat
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class TrainingPlanViewModel(
    private val repository: TrainingPlanRepository
) : ViewModel() {

    val templates: StateFlow<List<WorkoutTemplate>> = repository.observeTemplates()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val templatesWithSegments: StateFlow<List<WorkoutTemplateWithSegments>> =
        repository.observeTemplatesWithSegments()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val plans: StateFlow<List<TrainingPlan>> = repository.observePlans()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val activeSchedule = repository.observeActiveSchedule()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val activeScheduledWorkouts = repository.observeActiveScheduledWorkouts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun observePlanDays(planId: Long) = repository.observePlanDays(planId)

    suspend fun loadTemplate(id: Long) = repository.getTemplateWithSegments(id)

    suspend fun loadPlan(id: Long) = repository.getPlanWithDays(id)

    suspend fun getActivePlan() = repository.getActivePlan()

    suspend fun listPlans() = repository.observePlans().first()

    suspend fun getScheduledForDate(dateMillis: Long) =
        repository.getScheduledWorkoutForDate(dateMillis)

    suspend fun saveTemplate(
        id: Long,
        name: String,
        type: WorkoutType,
        icon: TrainingIcon,
        segments: List<WorkoutTemplateSegment>
    ): Long {
        return repository.saveTemplate(
            WorkoutTemplate(
                id = id,
                name = name,
                workoutType = type,
                iconKey = icon.name
            ),
            segments
        )
    }

    fun deleteTemplate(template: WorkoutTemplate) {
        viewModelScope.launch { repository.deleteTemplate(template) }
    }

    suspend fun savePlan(
        id: Long,
        name: String,
        durationDays: Int,
        icon: TrainingIcon
    ): Long {
        return repository.savePlan(
            TrainingPlan(
                id = id,
                name = name,
                durationDays = durationDays.coerceAtLeast(1),
                iconKey = icon.name
            )
        )
    }

    fun deletePlan(plan: TrainingPlan) {
        viewModelScope.launch { repository.deletePlan(plan) }
    }

    suspend fun setDayTemplate(planId: Long, dayIndex: Int, templateId: Long?) {
        repository.setPlanDayTemplate(planId, dayIndex, templateId)
    }

    suspend fun repeatPattern(planId: Long, indices: List<Int>) {
        repository.repeatPatternToEnd(planId, indices)
    }

    suspend fun applyToCalendar(planId: Long, startMillis: Long): Long {
        return repository.applyPlanToCalendar(planId, startMillis)
    }

    suspend fun exportBackupJson(packageName: String): String {
        val (templates, plans) = repository.buildExportSnapshot()
        if (templates.isEmpty() && plans.isEmpty()) {
            throw IllegalStateException("empty")
        }
        return TrainingPlanBackupFormat.toBackupJson(templates, plans, packageName)
    }

    suspend fun importBackupJson(json: String): TrainingPlanBackupFormat.ImportResult {
        val parsed = TrainingPlanBackupFormat.parseBackupJson(json)
        return repository.importBackup(parsed)
    }

    suspend fun todaysWorkout() = repository.getTodaysScheduledWorkout()

    fun deactivateSchedule() {
        viewModelScope.launch { repository.deactivateActiveSchedule() }
    }

    suspend fun markDone(scheduledId: Long, workoutId: Long) {
        repository.markScheduledDone(scheduledId, workoutId)
    }
}

class TrainingPlanViewModelFactory(
    private val repository: TrainingPlanRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(TrainingPlanViewModel::class.java)) {
            return TrainingPlanViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel")
    }
}

/** Editable segment draft for the template editor UI. */
data class SegmentDraft(
    var title: String = "",
    var kind: SegmentKind = SegmentKind.WORK,
    var goalType: SegmentGoalType = SegmentGoalType.DURATION,
    /** Total duration in seconds (h/m/s pickers). Default 10 min. */
    var durationTotalSeconds: Int = 10 * 60,
    /** Total distance in meters (km + m pickers). Default 200 m. */
    var distanceTotalMeters: Int = 200,
    /** Target pace as total seconds per km; 0 means no target. */
    var paceTotalSeconds: Int = 0
) {
    fun toEntity(templateId: Long, order: Int): WorkoutTemplateSegment {
        val pace = if (paceTotalSeconds > 0) paceTotalSeconds / 60f else null
        return when (goalType) {
            SegmentGoalType.DURATION -> WorkoutTemplateSegment(
                templateId = templateId,
                sortOrder = order,
                kind = kind,
                title = title.trim(),
                goalType = goalType,
                durationMs = durationTotalSeconds.coerceAtLeast(1) * 1000L,
                distanceMeters = null,
                targetPaceMinPerKm = pace
            )
            SegmentGoalType.DISTANCE -> WorkoutTemplateSegment(
                templateId = templateId,
                sortOrder = order,
                kind = kind,
                title = title.trim(),
                goalType = goalType,
                durationMs = null,
                distanceMeters = distanceTotalMeters.coerceAtLeast(1).toFloat(),
                targetPaceMinPerKm = pace
            )
        }
    }

    companion object {
        fun fromEntity(segment: WorkoutTemplateSegment) = SegmentDraft(
            title = segment.title,
            kind = segment.kind,
            goalType = segment.goalType,
            durationTotalSeconds = segment.durationMs
                ?.let { (it / 1000L).toInt().coerceAtLeast(1) }
                ?: (10 * 60),
            distanceTotalMeters = segment.distanceMeters
                ?.toInt()
                ?.coerceAtLeast(1)
                ?: 200,
            paceTotalSeconds = segment.targetPaceMinPerKm
                ?.takeIf { it > 0f }
                ?.let { (it * 60f).toInt().coerceAtLeast(0) }
                ?: 0
        )
    }
}
