package com.example.runner.data

import androidx.room.Entity
import androidx.room.PrimaryKey
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
