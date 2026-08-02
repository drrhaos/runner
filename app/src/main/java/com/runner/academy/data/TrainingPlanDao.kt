package com.runner.academy.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface WorkoutTemplateDao {
    @Query("SELECT * FROM workout_templates ORDER BY updatedAt DESC")
    fun getAllTemplates(): Flow<List<WorkoutTemplate>>

    @Query("SELECT * FROM workout_templates WHERE id = :id")
    suspend fun getTemplateById(id: Long): WorkoutTemplate?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTemplate(template: WorkoutTemplate): Long

    @Update
    suspend fun updateTemplate(template: WorkoutTemplate)

    @Delete
    suspend fun deleteTemplate(template: WorkoutTemplate)

    @Query("SELECT * FROM workout_template_segments WHERE templateId = :templateId ORDER BY sortOrder ASC")
    suspend fun getSegmentsForTemplate(templateId: Long): List<WorkoutTemplateSegment>

    @Query("SELECT * FROM workout_template_segments WHERE templateId = :templateId ORDER BY sortOrder ASC")
    fun observeSegmentsForTemplate(templateId: Long): Flow<List<WorkoutTemplateSegment>>

    @Query("SELECT * FROM workout_template_segments ORDER BY templateId ASC, sortOrder ASC")
    fun getAllSegments(): Flow<List<WorkoutTemplateSegment>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSegment(segment: WorkoutTemplateSegment): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSegments(segments: List<WorkoutTemplateSegment>): List<Long>

    @Update
    suspend fun updateSegment(segment: WorkoutTemplateSegment)

    @Delete
    suspend fun deleteSegment(segment: WorkoutTemplateSegment)

    @Query("DELETE FROM workout_template_segments WHERE templateId = :templateId")
    suspend fun deleteSegmentsForTemplate(templateId: Long)
}

@Dao
interface TrainingPlanDao {
    @Query("SELECT * FROM training_plans ORDER BY updatedAt DESC")
    fun getAllPlans(): Flow<List<TrainingPlan>>

    @Query("SELECT * FROM training_plans WHERE id = :id")
    suspend fun getPlanById(id: Long): TrainingPlan?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlan(plan: TrainingPlan): Long

    @Update
    suspend fun updatePlan(plan: TrainingPlan)

    @Delete
    suspend fun deletePlan(plan: TrainingPlan)

    @Query("SELECT * FROM training_plan_days WHERE planId = :planId ORDER BY dayIndex ASC")
    suspend fun getDaysForPlan(planId: Long): List<TrainingPlanDay>

    @Query("SELECT * FROM training_plan_days WHERE planId = :planId ORDER BY dayIndex ASC")
    fun observeDaysForPlan(planId: Long): Flow<List<TrainingPlanDay>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDay(day: TrainingPlanDay): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDays(days: List<TrainingPlanDay>): List<Long>

    @Update
    suspend fun updateDay(day: TrainingPlanDay)

    @Query("DELETE FROM training_plan_days WHERE planId = :planId")
    suspend fun deleteDaysForPlan(planId: Long)

    @Query("DELETE FROM training_plan_days WHERE planId = :planId AND dayIndex >= :fromIndex")
    suspend fun deleteDaysFromIndex(planId: Long, fromIndex: Int)
}

@Dao
interface PlanScheduleDao {
    @Query("SELECT * FROM plan_schedules ORDER BY createdAt DESC")
    fun getAllSchedules(): Flow<List<PlanSchedule>>

    @Query("SELECT * FROM plan_schedules WHERE isActive = 1 LIMIT 1")
    fun observeActiveSchedule(): Flow<PlanSchedule?>

    @Query("SELECT * FROM plan_schedules WHERE isActive = 1 LIMIT 1")
    suspend fun getActiveSchedule(): PlanSchedule?

    @Query("SELECT * FROM plan_schedules WHERE id = :id")
    suspend fun getScheduleById(id: Long): PlanSchedule?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSchedule(schedule: PlanSchedule): Long

    @Update
    suspend fun updateSchedule(schedule: PlanSchedule)

    @Query("UPDATE plan_schedules SET isActive = 0 WHERE isActive = 1")
    suspend fun deactivateAll()

    @Delete
    suspend fun deleteSchedule(schedule: PlanSchedule)

    @Query("SELECT * FROM scheduled_workouts WHERE scheduleId = :scheduleId ORDER BY dateMillis ASC")
    suspend fun getScheduledWorkouts(scheduleId: Long): List<ScheduledWorkout>

    @Query("SELECT * FROM scheduled_workouts WHERE scheduleId = :scheduleId ORDER BY dateMillis ASC")
    fun observeScheduledWorkouts(scheduleId: Long): Flow<List<ScheduledWorkout>>

    @Query(
        """
        SELECT * FROM scheduled_workouts
        WHERE scheduleId = :scheduleId AND dateMillis BETWEEN :startMillis AND :endMillis
        ORDER BY dateMillis ASC
        """
    )
    fun observeScheduledInRange(
        scheduleId: Long,
        startMillis: Long,
        endMillis: Long
    ): Flow<List<ScheduledWorkout>>

    @Query(
        """
        SELECT * FROM scheduled_workouts
        WHERE scheduleId = :scheduleId AND dateMillis = :dateMillis
        LIMIT 1
        """
    )
    suspend fun getScheduledForDate(scheduleId: Long, dateMillis: Long): ScheduledWorkout?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertScheduledWorkouts(items: List<ScheduledWorkout>): List<Long>

    @Update
    suspend fun updateScheduledWorkout(item: ScheduledWorkout)

    @Query("DELETE FROM scheduled_workouts WHERE scheduleId = :scheduleId")
    suspend fun deleteScheduledForSchedule(scheduleId: Long)
}
