package com.runner.academy.util

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.runner.academy.data.Workout
import com.runner.academy.data.WorkoutType
import java.util.Date

/**
 * JSON backup format shared with export from older Runner builds,
 * including `feature/export-all-workouts-example` (`com.example.runner`):
 * formatVersion=1, no isFavorite, trackData as escaped JSON string without
 * after_gap/source on points.
 *
 * Parsing is manual via JsonObject so missing fields don't blow up —
 * Gson + Kotlin non-null defaults are unsafe.
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
        val isFavorite: Boolean = false,
        val intervalSegmentsJson: String? = null
    )

    data class WorkoutsBackupFile(
        val formatVersion: Int = FORMAT_VERSION,
        val exportedAt: String? = null,
        val packageName: String? = null,
        val workoutCount: Int = 0,
        val workouts: List<WorkoutBackupDto> = emptyList()
    )

    private val gson: Gson = GsonBuilder().disableHtmlEscaping().create()

    fun parseBackupJson(json: String): List<Workout> {
        val root = try {
            JsonParser.parseString(json).asJsonObject
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid workout backup JSON", e)
        }

        val workoutsEl = root.get("workouts")
            ?: throw IllegalArgumentException("Backup missing workouts array")
        if (!workoutsEl.isJsonArray) {
            throw IllegalArgumentException("Backup workouts must be an array")
        }
        val array = workoutsEl.asJsonArray
        if (array.isEmpty) {
            throw IllegalArgumentException("Backup contains no workouts")
        }

        return array.mapIndexed { index, element ->
            if (!element.isJsonObject) {
                throw IllegalArgumentException("Workout at index $index is not an object")
            }
            element.asJsonObject.toDto().toWorkout()
        }
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

    private fun JsonObject.toDto(): WorkoutBackupDto {
        return WorkoutBackupDto(
            id = longOr("id", 0L),
            dateMillis = longOr("dateMillis", System.currentTimeMillis()),
            distanceKm = floatOr("distanceKm", 0f),
            durationMs = longOr("durationMs", 0L),
            avgPace = floatOr("avgPace", 0f),
            calories = intOrNull("calories"),
            notes = stringOrNull("notes"),
            type = stringOr("type", WorkoutType.EASY_RUN.name),
            trackData = trackDataOrNull(),
            isFavorite = booleanOr("isFavorite", false),
            intervalSegmentsJson = stringOrNull("intervalSegmentsJson")
        )
    }

    private fun JsonObject.trackDataOrNull(): String? {
        val el = get("trackData") ?: return null
        return when {
            el.isJsonNull -> null
            el.isJsonPrimitive && el.asJsonPrimitive.isString -> el.asString
            // Older / hand-edited backups may store track as nested object
            el.isJsonObject || el.isJsonArray -> el.toString()
            else -> el.toString()
        }
    }

    private fun JsonObject.longOr(key: String, default: Long): Long {
        val el = get(key) ?: return default
        if (el.isJsonNull) return default
        return try {
            when {
                el.isJsonPrimitive && el.asJsonPrimitive.isNumber -> el.asLong
                el.isJsonPrimitive && el.asJsonPrimitive.isString ->
                    el.asString.toLongOrNull() ?: default
                else -> default
            }
        } catch (_: Exception) {
            default
        }
    }

    private fun JsonObject.floatOr(key: String, default: Float): Float {
        val el = get(key) ?: return default
        if (el.isJsonNull) return default
        return try {
            when {
                el.isJsonPrimitive && el.asJsonPrimitive.isNumber -> el.asFloat
                el.isJsonPrimitive && el.asJsonPrimitive.isString ->
                    el.asString.toFloatOrNull() ?: default
                else -> default
            }
        } catch (_: Exception) {
            default
        }
    }

    private fun JsonObject.intOrNull(key: String): Int? {
        val el = get(key) ?: return null
        if (el.isJsonNull) return null
        return try {
            when {
                el.isJsonPrimitive && el.asJsonPrimitive.isNumber -> el.asInt
                el.isJsonPrimitive && el.asJsonPrimitive.isString -> el.asString.toIntOrNull()
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun JsonObject.stringOr(key: String, default: String): String =
        stringOrNull(key) ?: default

    private fun JsonObject.stringOrNull(key: String): String? {
        val el = get(key) ?: return null
        if (el.isJsonNull) return null
        return try {
            if (el.isJsonPrimitive) el.asString else el.toString()
        } catch (_: Exception) {
            null
        }
    }

    private fun JsonObject.booleanOr(key: String, default: Boolean): Boolean {
        val el = get(key) ?: return default
        if (el.isJsonNull) return default
        return try {
            when {
                el.isJsonPrimitive && el.asJsonPrimitive.isBoolean -> el.asBoolean
                el.isJsonPrimitive && el.asJsonPrimitive.isNumber -> el.asInt != 0
                el.isJsonPrimitive && el.asJsonPrimitive.isString ->
                    when (el.asString.trim().lowercase()) {
                        "true", "1", "yes" -> true
                        "false", "0", "no" -> false
                        else -> default
                    }
                else -> default
            }
        } catch (_: Exception) {
            default
        }
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
            trackData = TrackDataJson.normalizeStored(trackData),
            isFavorite = isFavorite,
            intervalSegmentsJson = intervalSegmentsJson
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
        isFavorite = isFavorite,
        intervalSegmentsJson = intervalSegmentsJson
    )
}
