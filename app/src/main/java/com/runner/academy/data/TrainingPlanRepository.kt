package com.runner.academy.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.concurrent.TimeUnit

class TrainingPlanRepository(
    private val templateDao: WorkoutTemplateDao,
    private val planDao: TrainingPlanDao,
    private val scheduleDao: PlanScheduleDao
) {

    fun observeTemplates(): Flow<List<WorkoutTemplate>> = templateDao.getAllTemplates()

    fun observePlans(): Flow<List<TrainingPlan>> = planDao.getAllPlans()

    fun observeActiveSchedule(): Flow<PlanSchedule?> = scheduleDao.observeActiveSchedule()

    fun observePlanDays(planId: Long): Flow<List<TrainingPlanDay>> =
        planDao.observeDaysForPlan(planId)

    suspend fun getTemplateWithSegments(templateId: Long): WorkoutTemplateWithSegments? =
        withContext(Dispatchers.IO) {
            val template = templateDao.getTemplateById(templateId) ?: return@withContext null
            WorkoutTemplateWithSegments(template, templateDao.getSegmentsForTemplate(templateId))
        }

    suspend fun saveTemplate(
        template: WorkoutTemplate,
        segments: List<WorkoutTemplateSegment>
    ): Long = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val id = if (template.id == 0L) {
            templateDao.insertTemplate(template.copy(createdAt = now, updatedAt = now))
        } else {
            templateDao.updateTemplate(template.copy(updatedAt = now))
            templateDao.deleteSegmentsForTemplate(template.id)
            template.id
        }
        val ordered = segments.mapIndexed { index, segment ->
            segment.copy(id = 0, templateId = id, sortOrder = index)
        }
        if (ordered.isNotEmpty()) {
            templateDao.insertSegments(ordered)
        }
        id
    }

    suspend fun deleteTemplate(template: WorkoutTemplate) = withContext(Dispatchers.IO) {
        templateDao.deleteTemplate(template)
    }

    suspend fun getPlanWithDays(planId: Long): Pair<TrainingPlan, List<TrainingPlanDay>>? =
        withContext(Dispatchers.IO) {
            val plan = planDao.getPlanById(planId) ?: return@withContext null
            plan to planDao.getDaysForPlan(planId)
        }

    suspend fun savePlan(plan: TrainingPlan, ensureDays: Boolean = true): Long =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val id = if (plan.id == 0L) {
                planDao.insertPlan(plan.copy(createdAt = now, updatedAt = now))
            } else {
                planDao.updatePlan(plan.copy(updatedAt = now))
                plan.id
            }
            if (ensureDays) {
                ensurePlanDays(id, plan.durationDays)
            }
            id
        }

    suspend fun deletePlan(plan: TrainingPlan) = withContext(Dispatchers.IO) {
        planDao.deletePlan(plan)
    }

    suspend fun setPlanDayTemplate(planId: Long, dayIndex: Int, templateId: Long?) =
        withContext(Dispatchers.IO) {
            val existing = planDao.getDaysForPlan(planId).find { it.dayIndex == dayIndex }
            if (existing != null) {
                planDao.updateDay(existing.copy(templateId = templateId))
            } else {
                planDao.insertDay(
                    TrainingPlanDay(planId = planId, dayIndex = dayIndex, templateId = templateId)
                )
            }
        }

    /**
     * Takes selected contiguous day indices (pattern), and repeats that block
     * from the end of the pattern until [TrainingPlan.durationDays].
     */
    suspend fun repeatPatternToEnd(planId: Long, patternDayIndices: List<Int>) =
        withContext(Dispatchers.IO) {
            if (patternDayIndices.isEmpty()) return@withContext
            val plan = planDao.getPlanById(planId) ?: return@withContext
            val days = planDao.getDaysForPlan(planId).associateBy { it.dayIndex }
            val sorted = patternDayIndices.distinct().sorted()
            val patternStart = sorted.first()
            val patternEnd = sorted.last()
            val patternLength = patternEnd - patternStart + 1
            if (patternLength <= 0) return@withContext

            val patternTemplates = (patternStart..patternEnd).map { idx ->
                days[idx]?.templateId
            }

            val toInsert = mutableListOf<TrainingPlanDay>()
            var writeIndex = patternEnd + 1
            while (writeIndex < plan.durationDays) {
                val offset = (writeIndex - (patternEnd + 1)) % patternLength
                val templateId = patternTemplates[offset]
                val existing = days[writeIndex]
                if (existing != null) {
                    planDao.updateDay(existing.copy(templateId = templateId))
                } else {
                    toInsert.add(
                        TrainingPlanDay(
                            planId = planId,
                            dayIndex = writeIndex,
                            templateId = templateId
                        )
                    )
                }
                writeIndex++
            }
            if (toInsert.isNotEmpty()) {
                planDao.insertDays(toInsert)
            }
            planDao.updatePlan(plan.copy(updatedAt = System.currentTimeMillis()))
        }

    /**
     * Deactivates other schedules, creates a new active schedule from [startDateMillis]
     * and materializes [ScheduledWorkout] rows for each plan day.
     */
    suspend fun applyPlanToCalendar(planId: Long, startDateMillis: Long): Long =
        withContext(Dispatchers.IO) {
            val plan = planDao.getPlanById(planId)
                ?: throw IllegalArgumentException("Plan not found")
            val days = planDao.getDaysForPlan(planId)
            val dayMap = days.associateBy { it.dayIndex }

            scheduleDao.deactivateAll()
            val scheduleId = scheduleDao.insertSchedule(
                PlanSchedule(
                    planId = planId,
                    startDateMillis = startOfDay(startDateMillis),
                    isActive = true
                )
            )

            val start = startOfDay(startDateMillis)
            val items = (0 until plan.durationDays).map { dayIndex ->
                val templateId = dayMap[dayIndex]?.templateId
                val date = start + TimeUnit.DAYS.toMillis(dayIndex.toLong())
                ScheduledWorkout(
                    scheduleId = scheduleId,
                    dateMillis = date,
                    dayIndex = dayIndex,
                    templateId = templateId,
                    status = if (templateId == null) {
                        ScheduledWorkoutStatus.REST
                    } else {
                        ScheduledWorkoutStatus.PLANNED
                    }
                )
            }
            scheduleDao.insertScheduledWorkouts(items)
            scheduleId
        }

    suspend fun getTodaysScheduledWorkout(
        nowMillis: Long = System.currentTimeMillis()
    ): ScheduledWorkoutWithTemplate? = withContext(Dispatchers.IO) {
        val schedule = scheduleDao.getActiveSchedule() ?: return@withContext null
        val dayStart = startOfDay(nowMillis)
        val scheduled = scheduleDao.getScheduledForDate(schedule.id, dayStart)
            ?: return@withContext null
        val template = scheduled.templateId?.let { templateDao.getTemplateById(it) }
        val segments = scheduled.templateId?.let { templateDao.getSegmentsForTemplate(it) }
            ?: emptyList()
        ScheduledWorkoutWithTemplate(scheduled, template, segments)
    }

    suspend fun markScheduledDone(scheduledId: Long, workoutId: Long) =
        withContext(Dispatchers.IO) {
            val schedule = scheduleDao.getActiveSchedule() ?: return@withContext
            val item = scheduleDao.getScheduledWorkouts(schedule.id)
                .find { it.id == scheduledId } ?: return@withContext
            scheduleDao.updateScheduledWorkout(
                item.copy(
                    status = ScheduledWorkoutStatus.DONE,
                    completedWorkoutId = workoutId
                )
            )
        }

    suspend fun deactivateActiveSchedule() = withContext(Dispatchers.IO) {
        scheduleDao.deactivateAll()
    }

    private suspend fun ensurePlanDays(planId: Long, durationDays: Int) {
        val existing = planDao.getDaysForPlan(planId).associateBy { it.dayIndex }
        val missing = (0 until durationDays)
            .filter { it !in existing }
            .map { TrainingPlanDay(planId = planId, dayIndex = it, templateId = null) }
        if (missing.isNotEmpty()) {
            planDao.insertDays(missing)
        }
        if (existing.keys.any { it >= durationDays }) {
            planDao.deleteDaysFromIndex(planId, durationDays)
        }
    }

    companion object {
        fun startOfDay(millis: Long): Long {
            val cal = Calendar.getInstance()
            cal.timeInMillis = millis
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            return cal.timeInMillis
        }
    }
}
