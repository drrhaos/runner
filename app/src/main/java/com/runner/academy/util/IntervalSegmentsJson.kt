package com.runner.academy.util

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.runner.academy.data.SegmentGoalType
import com.runner.academy.data.SegmentKind
import com.runner.academy.data.WorkoutTemplateSegment

/**
 * Snapshot of template intervals stored on a completed [com.runner.academy.data.Workout]
 * so detail charts can split by plan segments instead of km/mile.
 *
 * Uses manual JsonObject read/write (not Gson reflective field names) so release R8
 * cannot corrupt keys like `kind` / `goalType`.
 */
object IntervalSegmentsJson {

    fun toJson(segments: List<WorkoutTemplateSegment>): String? {
        if (segments.isEmpty()) return null
        val array = JsonArray()
        segments.sortedBy { it.sortOrder }.forEach { seg ->
            array.add(
                JsonObject().apply {
                    addProperty("sortOrder", seg.sortOrder)
                    addProperty("kind", seg.kind.name)
                    addProperty("title", seg.title)
                    addProperty("goalType", seg.goalType.name)
                    seg.durationMs?.let { addProperty("durationMs", it) }
                    seg.distanceMeters?.let { addProperty("distanceMeters", it) }
                    seg.targetPaceMinPerKm?.let { addProperty("targetPaceMinPerKm", it) }
                }
            )
        }
        return array.toString()
    }

    fun parse(json: String?): List<WorkoutTemplateSegment> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val element = JsonParser.parseString(json)
            val array = when {
                element.isJsonArray -> element.asJsonArray
                element.isJsonObject && element.asJsonObject.has("segments") -> {
                    val nested = element.asJsonObject.get("segments")
                    if (nested != null && nested.isJsonArray) nested.asJsonArray else return emptyList()
                }
                else -> return emptyList()
            }
            array.mapIndexedNotNull { index, el ->
                if (!el.isJsonObject) return@mapIndexedNotNull null
                el.asJsonObject.toSegment(index)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun JsonObject.toSegment(fallbackOrder: Int): WorkoutTemplateSegment {
        val kindName = stringOr("kind", SegmentKind.WORK.name)
        val goalName = stringOr("goalType", SegmentGoalType.DURATION.name)
        val kind = SegmentKind.entries.find { it.name.equals(kindName, true) } ?: SegmentKind.WORK
        val goal = SegmentGoalType.entries.find { it.name.equals(goalName, true) }
            ?: SegmentGoalType.DURATION
        val order = intOr("sortOrder", 0)
        return WorkoutTemplateSegment(
            id = 0,
            templateId = 0,
            sortOrder = if (order != 0 || fallbackOrder == 0) order else fallbackOrder,
            kind = kind,
            title = stringOr("title", ""),
            goalType = goal,
            durationMs = longOrNull("durationMs"),
            distanceMeters = floatOrNull("distanceMeters"),
            targetPaceMinPerKm = floatOrNull("targetPaceMinPerKm")
        )
    }

    private fun JsonObject.stringOr(key: String, default: String): String {
        val el = get(key) ?: return default
        if (el.isJsonNull) return default
        return try {
            if (el.isJsonPrimitive) el.asString else default
        } catch (_: Exception) {
            default
        }
    }

    private fun JsonObject.intOr(key: String, default: Int): Int {
        val el = get(key) ?: return default
        if (el.isJsonNull) return default
        return try {
            when {
                el.isJsonPrimitive && el.asJsonPrimitive.isNumber -> el.asInt
                el.isJsonPrimitive && el.asJsonPrimitive.isString ->
                    el.asString.toIntOrNull() ?: default
                else -> default
            }
        } catch (_: Exception) {
            default
        }
    }

    private fun JsonObject.longOrNull(key: String): Long? {
        val el = get(key) ?: return null
        if (el.isJsonNull) return null
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

    private fun JsonObject.floatOrNull(key: String): Float? {
        val el = get(key) ?: return null
        if (el.isJsonNull) return null
        return try {
            when {
                el.isJsonPrimitive && el.asJsonPrimitive.isNumber -> el.asFloat
                el.isJsonPrimitive && el.asJsonPrimitive.isString -> el.asString.toFloatOrNull()
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }
}
