package com.runner.academy.util

import com.google.gson.GsonBuilder
import com.google.gson.JsonSyntaxException
import com.runner.academy.data.Workout
import com.runner.academy.data.WorkoutType
import java.util.Date

/**
 * JSON backup format shared with export from older Runner builds
 * (`runner_workouts_backup_*.json`, formatVersion = 1).
 */
object WorkoutBackupFormat {

    const val FORMAT_VERSION = 1

    data class WorkoutBackupDto(
        val id: Long = 0,
        val dateMillis: Long,
        val distanceKm: Float,
        val durationMs: Long,
        val avgPace: Float,
        val calories: Int? = null,
        val notes: String? = null,
        val type: String = WorkoutType.EASY_RUN.name,
        val trackData: String? = null,
        val isFavorite: Boolean = false
    )

    data class WorkoutsBackupFile(
        val formatVersion: Int = FORMAT_VERSION,
        val exportedAt: String? = null,
        val packageName: String? = null,
        val workoutCount: Int = 0,
        val workouts: List<WorkoutBackupDto> = emptyList()
    )

    private val gson = GsonBuilder().disableHtmlEscaping().create()

    fun parseBackupJson(json: String): List<Workout> {
        val payload = try {
            gson.fromJson(json, WorkoutsBackupFile::class.java)
        } catch (e: JsonSyntaxException) {
            throw IllegalArgumentException("Invalid workout backup JSON", e)
        } ?: throw IllegalArgumentException("Empty workout backup JSON")

        if (payload.workouts.isEmpty()) {
            throw IllegalArgumentException("Backup contains no workouts")
        }
        return payload.workouts.map { it.toWorkout() }
    }

    fun toBackupJson(workouts: List<Workout>, packageName: String): String {
        val payload = WorkoutsBackupFile(
            formatVersion = FORMAT_VERSION,
            exportedAt = java.time.Instant.now().toString(),
            packageName = packageName,
            workoutCount = workouts.size,
            workouts = workouts.map { it.toDto() }
        )
        return gson.toJson(payload)
    }

    fun getBackupJsonFileName(): String {
        val stamp = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss")
            .withZone(java.time.ZoneOffset.UTC)
            .format(java.time.Instant.now())
        return "runner_workouts_backup_$stamp.json"
    }

    private fun WorkoutBackupDto.toWorkout(): Workout {
        val workoutType = WorkoutType.entries.find { it.name.equals(type, ignoreCase = true) }
            ?: WorkoutType.EASY_RUN
        return Workout(
            id = 0,
            date = Date(dateMillis),
            distance = distanceKm.coerceAtLeast(0f),
            duration = durationMs.coerceAtLeast(0L),
            avgPace = avgPace.coerceAtLeast(0f),
            calories = calories,
            notes = notes,
            type = workoutType,
            trackData = trackData,
            isFavorite = isFavorite
        )
    }

    private fun Workout.toDto() = WorkoutBackupDto(
        id = id,
        dateMillis = date.time,
        distanceKm = distance,
        durationMs = duration,
        avgPace = avgPace,
        calories = calories,
        notes = notes,
        type = type.name,
        trackData = trackData,
        isFavorite = isFavorite
    )
}
