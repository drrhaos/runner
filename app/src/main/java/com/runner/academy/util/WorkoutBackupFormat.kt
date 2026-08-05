package com.runner.academy.util

import com.google.gson.JsonArray
import com.google.gson.JsonElement
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
 * Also accepts release backups where R8 obfuscated Gson field names to
 * single-letter keys (a/b/c/…), produced before export switched to explicit
 * [JsonObject] property names.
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

    fun parseBackupJson(json: String): List<Workout> {
        val root = try {
            JsonParser.parseString(json).asJsonObject
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid workout backup JSON", e)
        }

        val workoutsEl = root.workoutsArrayOrNull()
            ?: throw IllegalArgumentException("Backup missing workouts array")
        if (workoutsEl.isEmpty) {
            throw IllegalArgumentException("Backup contains no workouts")
        }

        return workoutsEl.mapIndexed { index, element ->
            if (!element.isJsonObject) {
                throw IllegalArgumentException("Workout at index $index is not an object")
            }
            element.asJsonObject.toDto().toWorkout()
        }
    }

    fun toBackupJson(workouts: List<Workout>, packageName: String): String {
        val workoutsArray = JsonArray()
        workouts.forEach { workout ->
            workoutsArray.add(
                JsonObject().apply {
                    addProperty("id", workout.id)
                    addProperty("dateMillis", workout.date.time)
                    addProperty("distanceKm", workout.distance)
                    addProperty("durationMs", workout.duration)
                    addProperty("avgPace", workout.avgPace)
                    workout.calories?.let { addProperty("calories", it) }
                    workout.notes?.let { addProperty("notes", it) }
                    addProperty("type", workout.type.name)
                    workout.trackData?.let { addProperty("trackData", it) }
                    addProperty("isFavorite", workout.isFavorite)
                    workout.intervalSegmentsJson?.let { addProperty("intervalSegmentsJson", it) }
                }
            )
        }
        return JsonObject().apply {
            addProperty("formatVersion", FORMAT_VERSION)
            addProperty("exportedAt", java.time.Instant.now().toString())
            addProperty("packageName", packageName)
            addProperty("workoutCount", workouts.size)
            add("workouts", workoutsArray)
        }.toString()
    }

    fun getBackupJsonFileName(): String {
        val stamp = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss")
            .withZone(java.time.ZoneOffset.UTC)
            .format(java.time.Instant.now())
        return "runner_workouts_backup_$stamp.json"
    }

    /**
     * Named keys from current export, or R8/Gson single-letter aliases from
     * older release builds: exportedAt/packageName/workoutCount/workouts → a/b/c/d
     * (formatVersion was absent in that DTO layout).
     */
    private fun JsonObject.workoutsArrayOrNull(): JsonArray? {
        val named = get("workouts")
        if (named != null && named.isJsonArray) return named.asJsonArray
        val obfuscated = get("d")
        if (obfuscated != null && obfuscated.isJsonArray) return obfuscated.asJsonArray
        return null
    }

    private fun JsonObject.toDto(): WorkoutBackupDto {
        // Field order in the obfuscated Gson DTO:
        // id, dateMillis, distanceKm, durationMs, avgPace, calories, notes,
        // type, trackData, isFavorite [, intervalSegmentsJson]
        // → a … j [, k]. Null notes are omitted, so letters after notes still match.
        return WorkoutBackupDto(
            id = longOr(listOf("id", "a"), 0L),
            dateMillis = longOr(listOf("dateMillis", "b"), System.currentTimeMillis()),
            distanceKm = floatOr(listOf("distanceKm", "c"), 0f),
            durationMs = longOr(listOf("durationMs", "d"), 0L),
            avgPace = floatOr(listOf("avgPace", "e"), 0f),
            calories = intOrNull(listOf("calories", "f")),
            notes = stringOrNull(listOf("notes", "g")),
            type = stringOr(listOf("type", "h"), WorkoutType.EASY_RUN.name),
            trackData = trackDataOrNull(listOf("trackData", "i")),
            isFavorite = booleanOr(listOf("isFavorite", "j"), false),
            intervalSegmentsJson = stringOrNull(listOf("intervalSegmentsJson", "k"))
        )
    }

    private fun JsonObject.trackDataOrNull(keys: List<String>): String? {
        val el = firstPresent(keys) ?: return null
        return when {
            el.isJsonNull -> null
            el.isJsonPrimitive && el.asJsonPrimitive.isString -> el.asString
            // Older / hand-edited backups may store track as nested object
            el.isJsonObject || el.isJsonArray -> el.toString()
            else -> el.toString()
        }
    }

    private fun JsonObject.firstPresent(keys: List<String>): JsonElement? {
        for (key in keys) {
            val el = get(key) ?: continue
            if (!el.isJsonNull) return el
        }
        return null
    }

    private fun JsonObject.longOr(keys: List<String>, default: Long): Long {
        val el = firstPresent(keys) ?: return default
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

    private fun JsonObject.floatOr(keys: List<String>, default: Float): Float {
        val el = firstPresent(keys) ?: return default
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

    private fun JsonObject.intOrNull(keys: List<String>): Int? {
        val el = firstPresent(keys) ?: return null
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

    private fun JsonObject.stringOr(keys: List<String>, default: String): String =
        stringOrNull(keys) ?: default

    private fun JsonObject.stringOrNull(keys: List<String>): String? {
        val el = firstPresent(keys) ?: return null
        return try {
            if (el.isJsonPrimitive) el.asString else el.toString()
        } catch (_: Exception) {
            null
        }
    }

    private fun JsonObject.booleanOr(keys: List<String>, default: Boolean): Boolean {
        val el = firstPresent(keys) ?: return default
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
}
