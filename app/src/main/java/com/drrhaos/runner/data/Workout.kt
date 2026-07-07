package com.drrhaos.runner.data

import android.content.Context
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.drrhaos.runner.R
import java.util.Date

@Entity(tableName = "workouts")
data class Workout(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val date: Date,
    val distance: Float, // в километрах
    val duration: Long, // в миллисекундах
    val avgPace: Float, // темп в минутах на километр
    val calories: Int?,
    val notes: String?,
    val type: WorkoutType,
    val trackData: String? // JSON с траекторией и временными метками
)

enum class WorkoutType {
    EASY_RUN,
    TEMPO_RUN,
    INTERVAL_TRAINING,
    LONG_RUN,
    RECOVERY_RUN,
    RACE
}

fun WorkoutType.displayName(context: Context): String = when (this) {
    WorkoutType.EASY_RUN -> context.getString(R.string.workout_type_easy_run)
    WorkoutType.TEMPO_RUN -> context.getString(R.string.workout_type_tempo_run)
    WorkoutType.INTERVAL_TRAINING -> context.getString(R.string.workout_type_interval_training)
    WorkoutType.LONG_RUN -> context.getString(R.string.workout_type_long_run)
    WorkoutType.RECOVERY_RUN -> context.getString(R.string.workout_type_recovery_run)
    WorkoutType.RACE -> context.getString(R.string.workout_type_competition)
}

/** Макс. разумная скорость между точками (м/с) для фильтра выбросов GPS — зависит от типа тренировки */
fun WorkoutType.maxReasonableGpsSpeedMps(): Float = when (this) {
    WorkoutType.RACE,
    WorkoutType.INTERVAL_TRAINING -> 18f // ~65 км/ч (спуск / интервалы)
    WorkoutType.TEMPO_RUN -> 16f
    WorkoutType.LONG_RUN,
    WorkoutType.EASY_RUN,
    WorkoutType.RECOVERY_RUN -> 14f // ~50 км/ч
}
