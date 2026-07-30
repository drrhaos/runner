package com.runner.academy.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Kind of interval inside a base workout template. */
enum class SegmentKind {
    WARMUP,
    WORK,
    RECOVERY,
    COOLDOWN,
    CUSTOM
}

/**
 * Primary completion goal for a segment (MVP: one primary goal).
 * Optional [targetPaceMinPerKm] is guidance only.
 */
enum class SegmentGoalType {
    DURATION,
    DISTANCE
}

enum class ScheduledWorkoutStatus {
    PLANNED,
    DONE,
    SKIPPED,
    REST
}

@Entity(tableName = "workout_templates")
data class WorkoutTemplate(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val workoutType: WorkoutType = WorkoutType.INTERVAL_TRAINING,
    /** Intensity icon key, see [TrainingIcon]. */
    val iconKey: String = TrainingIcon.INTERVAL.name,
    val notes: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "workout_template_segments",
    foreignKeys = [
        ForeignKey(
            entity = WorkoutTemplate::class,
            parentColumns = ["id"],
            childColumns = ["templateId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("templateId")]
)
data class WorkoutTemplateSegment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val templateId: Long,
    val sortOrder: Int,
    val kind: SegmentKind = SegmentKind.WORK,
    val title: String,
    val goalType: SegmentGoalType = SegmentGoalType.DURATION,
    /** Target duration in milliseconds when [goalType] is DURATION. */
    val durationMs: Long? = null,
    /** Target distance in meters when [goalType] is DISTANCE. */
    val distanceMeters: Float? = null,
    /** Optional target pace in minutes per km (guidance). */
    val targetPaceMinPerKm: Float? = null
)

@Entity(tableName = "training_plans")
data class TrainingPlan(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** Total length of the plan in days (e.g. 56 for 8 weeks). */
    val durationDays: Int,
    /** Plan icon key, see [TrainingIcon]. */
    val iconKey: String = TrainingIcon.PLAN.name,
    val notes: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "training_plan_days",
    foreignKeys = [
        ForeignKey(
            entity = TrainingPlan::class,
            parentColumns = ["id"],
            childColumns = ["planId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = WorkoutTemplate::class,
            parentColumns = ["id"],
            childColumns = ["templateId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [Index("planId"), Index("templateId"), Index(value = ["planId", "dayIndex"], unique = true)]
)
data class TrainingPlanDay(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val planId: Long,
    /** 0-based day index within the plan. */
    val dayIndex: Int,
    /** Null means rest day. */
    val templateId: Long? = null
)

@Entity(
    tableName = "plan_schedules",
    foreignKeys = [
        ForeignKey(
            entity = TrainingPlan::class,
            parentColumns = ["id"],
            childColumns = ["planId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("planId")]
)
data class PlanSchedule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val planId: Long,
    /** Start date as epoch millis at local midnight. */
    val startDateMillis: Long,
    val isActive: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "scheduled_workouts",
    foreignKeys = [
        ForeignKey(
            entity = PlanSchedule::class,
            parentColumns = ["id"],
            childColumns = ["scheduleId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = WorkoutTemplate::class,
            parentColumns = ["id"],
            childColumns = ["templateId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [Index("scheduleId"), Index("dateMillis"), Index("templateId")]
)
data class ScheduledWorkout(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val scheduleId: Long,
    val dateMillis: Long,
    val dayIndex: Int,
    val templateId: Long? = null,
    val status: ScheduledWorkoutStatus = ScheduledWorkoutStatus.PLANNED,
    val completedWorkoutId: Long? = null
)

/** Template with ordered segments for UI / tracking. */
data class WorkoutTemplateWithSegments(
    val template: WorkoutTemplate,
    val segments: List<WorkoutTemplateSegment>
)

data class ScheduledWorkoutWithTemplate(
    val scheduled: ScheduledWorkout,
    val template: WorkoutTemplate?,
    val segments: List<WorkoutTemplateSegment>
)
