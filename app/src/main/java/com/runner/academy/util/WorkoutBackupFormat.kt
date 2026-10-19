package com.runner.academy.util

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.runner.academy.data.PauseKind
import com.runner.academy.data.Workout
import com.runner.academy.data.WorkoutType
import java.io.Reader
import java.io.StringReader
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
 *
 * formatVersion=2 (DB schema 7) adds movingDurationMs, excludeFromRecords, avgHeartRate and
 * maxHeartRate. Derived metrics (elevation, cadence, route preview, records) are not exported:
 * imported rows get metricsVersion 0 and are recomputed from the track. Older builds read
 * format 2 too — they ignore unknown keys. The heart rate is derived from the track too since
 * metrics version 7; its keys stay in the format and the recompute overwrites them.
 */
object WorkoutBackupFormat {

    const val FORMAT_VERSION = 2

    /** The workouts array: `workouts`, or `d` in obfuscated older builds. */
    private val WORKOUTS_KEYS = setOf("workouts", "d")

    data class WorkoutBackupDto(
        val id: Long = 0,
        val dateMillis: Long,
        val distanceKm: Float,
        val durationMs: Long,
        /** Format 2; absent in format 1 (= durationMs). */
        val movingDurationMs: Long? = null,
        val avgPace: Float,
        val calories: Int? = null,
        val notes: String? = null,
        val type: String = WorkoutType.EASY_RUN.name,
        val trackData: String? = null,
        val isFavorite: Boolean = false,
        val intervalSegmentsJson: String? = null,
        val excludeFromRecords: Boolean = false,
        val avgHeartRate: Int? = null,
        val maxHeartRate: Int? = null
    )

    data class WorkoutsBackupFile(
        val formatVersion: Int = FORMAT_VERSION,
        val exportedAt: String? = null,
        val packageName: String? = null,
        val workoutCount: Int = 0,
        val workouts: List<WorkoutBackupDto> = emptyList()
    )

    fun parseBackupJson(json: String): List<Workout> =
        backupBatches(StringReader(json), batchSize = Int.MAX_VALUE).flatten().toList()

    /**
     * The workouts of a backup [batchSize] at a time, read as they are consumed: the file is
     * never held whole. The first [skip] rows (imported before) are passed over unconverted.
     * [onCount] gets the declared `workoutCount` when the file has one before its rows. Throws
     * [IllegalArgumentException] on a broken file or one without workouts, when reached.
     */
    fun backupBatches(
        reader: Reader,
        batchSize: Int,
        skip: Int = 0,
        onCount: (Int) -> Unit = {}
    ): Sequence<List<Workout>> = sequence {
        JsonReader(reader).use { json ->
            var found = false
            var rows = 0
            try {
                json.beginObject()
                while (json.hasNext()) {
                    val name = json.nextName()
                    when {
                        name in WORKOUTS_KEYS && !found && json.peek() == JsonToken.BEGIN_ARRAY -> {
                            found = true
                            json.beginArray()
                            val batch = mutableListOf<Workout>()
                            while (json.hasNext()) {
                                val element = JsonParser.parseReader(json)
                                if (!element.isJsonObject) {
                                    throw IllegalArgumentException("Workout at index $rows is not an object")
                                }
                                if (rows >= skip) batch += element.asJsonObject.toDto().toWorkout()
                                rows++
                                if (batch.size == batchSize) {
                                    yield(batch.toList())
                                    batch.clear()
                                }
                            }
                            json.endArray()
                            if (batch.isNotEmpty()) yield(batch.toList())
                        }
                        name == "workoutCount" && json.peek() == JsonToken.NUMBER -> onCount(json.nextInt())
                        else -> json.skipValue()
                    }
                }
                json.endObject()
            } catch (e: IllegalArgumentException) {
                throw e
            } catch (e: Exception) {
                throw IllegalArgumentException("Invalid workout backup JSON", e)
            }
            if (!found) throw IllegalArgumentException("Backup missing workouts array")
            if (rows == 0) throw IllegalArgumentException("Backup contains no workouts")
        }
    }

    fun toBackupJson(workouts: List<Workout>, packageName: String): String {
        val workoutsArray = JsonArray()
        workouts.forEach { workoutsArray.add(it.toJsonObject()) }
        return JsonObject().apply {
            addProperty("formatVersion", FORMAT_VERSION)
            addProperty("exportedAt", java.time.Instant.now().toString())
            addProperty("packageName", packageName)
            addProperty("workoutCount", workouts.size)
            add("workouts", workoutsArray)
        }.toString()
    }

    /** One workout as a backup row holds it: [parseWorkoutJson] reads it back. */
    fun toWorkoutJson(workout: Workout): String = workout.toJsonObject().toString()

    /** A workout written by [toWorkoutJson], read as a backup row is (metrics to be derived again). */
    fun parseWorkoutJson(json: String): Workout = JsonParser.parseString(json).asJsonObject.toDto().toWorkout()

    private fun Workout.toJsonObject() = JsonObject().apply {
        addProperty("id", id)
        addProperty("dateMillis", date.time)
        addProperty("distanceKm", distance)
        addProperty("durationMs", duration)
        addProperty("movingDurationMs", movingDuration)
        addProperty("avgPace", avgPace)
        calories?.let { addProperty("calories", it) }
        notes?.let { addProperty("notes", it) }
        addProperty("type", type.name)
        trackData?.let { addProperty("trackData", it) }
        addProperty("isFavorite", isFavorite)
        intervalSegmentsJson?.let { addProperty("intervalSegmentsJson", it) }
        addProperty("excludeFromRecords", excludeFromRecords)
        avgHeartRate?.let { addProperty("avgHeartRate", it) }
        maxHeartRate?.let { addProperty("maxHeartRate", it) }
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
    private fun JsonObject.toDto(): WorkoutBackupDto {
        // Field order in the obfuscated Gson DTO:
        // id, dateMillis, distanceKm, durationMs, avgPace, calories, notes,
        // type, trackData, isFavorite [, intervalSegmentsJson]
        // → a … j [, k]. Null notes are omitted, so letters after notes still match.
        // Format 2 keys never had obfuscated aliases.
        return WorkoutBackupDto(
            id = longOr(listOf("id", "a"), 0L),
            dateMillis = longOr(listOf("dateMillis", "b"), System.currentTimeMillis()),
            distanceKm = floatOr(listOf("distanceKm", "c"), 0f),
            durationMs = longOr(listOf("durationMs", "d"), 0L),
            movingDurationMs = longOrNull(listOf("movingDurationMs")),
            avgPace = floatOr(listOf("avgPace", "e"), 0f),
            calories = intOrNull(listOf("calories", "f")),
            notes = stringOrNull(listOf("notes", "g")),
            type = stringOr(listOf("type", "h"), WorkoutType.EASY_RUN.name),
            trackData = trackDataOrNull(listOf("trackData", "i")),
            isFavorite = booleanOr(listOf("isFavorite", "j"), false),
            intervalSegmentsJson = stringOrNull(listOf("intervalSegmentsJson", "k")),
            excludeFromRecords = booleanOr(listOf("excludeFromRecords"), false),
            avgHeartRate = intOrNull(listOf("avgHeartRate")),
            maxHeartRate = intOrNull(listOf("maxHeartRate"))
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

    private fun JsonObject.longOr(keys: List<String>, default: Long): Long =
        longOrNull(keys) ?: default

    private fun JsonObject.longOrNull(keys: List<String>): Long? {
        val el = firstPresent(keys) ?: return null
        return try {
            when {
                el.isJsonPrimitive && el.asJsonPrimitive.isNumber -> el.asLong
                el.isJsonPrimitive && el.asJsonPrimitive.isString -> el.asString.toLongOrNull()
                else -> null
            }
        } catch (_: Exception) {
            null
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
        val distance = distanceKm.coerceAtLeast(0f)
        val duration = durationMs.coerceAtLeast(0L)
        // Format 1 has no moving time; a value out of 0 < m ≤ duration is not trusted: then the
        // track's auto-pauses tell it (none known → duration)
        val movingDuration = movingDurationMs?.takeIf { it in 1..duration }
            ?: movingFromPauses(duration, trackData)
        return Workout(
            id = 0,
            date = Date(dateMillis),
            distance = distance,
            duration = duration,
            movingDuration = movingDuration,
            // Recomputed like on every other path, not taken from the file
            avgPace = PaceMath.avgPace(distance, movingDuration),
            calories = calories,
            notes = notes,
            type = workoutType,
            trackData = TrackDataJson.normalizeStored(trackData),
            isFavorite = isFavorite,
            intervalSegmentsJson = intervalSegmentsJson,
            excludeFromRecords = excludeFromRecords,
            avgHeartRate = avgHeartRate,
            maxHeartRate = maxHeartRate,
            metricsVersion = 0
        )
    }

    /** duration − Σ auto-pauses of the track, if that stays in 0 < m ≤ duration; else duration. */
    private fun movingFromPauses(duration: Long, trackJson: String?): Long {
        val pauses = trackJson?.takeIf { it.contains("pauses") }?.let { TrackDataJson.parse(it)?.pauses }
        val moving = duration - TrackPauses.totalMs(pauses, PauseKind.AUTO)
        return moving.takeIf { it in 1..duration } ?: duration
    }
}
