package com.runner.academy.util

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.runner.academy.data.SegmentGoalType
import com.runner.academy.data.SegmentKind
import com.runner.academy.data.WorkoutTemplateSegment

/**
 * Snapshot of template intervals stored on a completed [com.runner.academy.data.Workout]
 * so detail charts can split by plan segments instead of km/mile.
 */
object IntervalSegmentsJson {

    data class SegmentDto(
        val sortOrder: Int = 0,
        val kind: String = SegmentKind.WORK.name,
        val title: String = "",
        val goalType: String = SegmentGoalType.DURATION.name,
        val durationMs: Long? = null,
        val distanceMeters: Float? = null,
        val targetPaceMinPerKm: Float? = null
    )

    private val gson: Gson = GsonBuilder().create()
    private val listType = object : TypeToken<List<SegmentDto>>() {}.type

    fun toJson(segments: List<WorkoutTemplateSegment>): String? {
        if (segments.isEmpty()) return null
        val dtos = segments.sortedBy { it.sortOrder }.map { seg ->
            SegmentDto(
                sortOrder = seg.sortOrder,
                kind = seg.kind.name,
                title = seg.title,
                goalType = seg.goalType.name,
                durationMs = seg.durationMs,
                distanceMeters = seg.distanceMeters,
                targetPaceMinPerKm = seg.targetPaceMinPerKm
            )
        }
        return gson.toJson(dtos)
    }

    fun parse(json: String?): List<WorkoutTemplateSegment> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val element = JsonParser.parseString(json)
            val dtos: List<SegmentDto> = when {
                element.isJsonArray -> gson.fromJson(element, listType)
                element.isJsonObject && element.asJsonObject.has("segments") -> {
                    gson.fromJson(element.asJsonObject.get("segments"), listType)
                }
                else -> emptyList()
            } ?: emptyList()
            dtos.mapIndexed { index, dto -> dto.toSegment(index) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun SegmentDto.toSegment(fallbackOrder: Int): WorkoutTemplateSegment {
        val kind = SegmentKind.entries.find { it.name.equals(kind, true) } ?: SegmentKind.WORK
        val goal = SegmentGoalType.entries.find { it.name.equals(goalType, true) }
            ?: SegmentGoalType.DURATION
        return WorkoutTemplateSegment(
            id = 0,
            templateId = 0,
            sortOrder = if (sortOrder != 0 || fallbackOrder == 0) sortOrder else fallbackOrder,
            kind = kind,
            title = title,
            goalType = goal,
            durationMs = durationMs,
            distanceMeters = distanceMeters,
            targetPaceMinPerKm = targetPaceMinPerKm
        )
    }
}
