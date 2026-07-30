package com.runner.academy.util

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.runner.academy.data.SegmentGoalType
import com.runner.academy.data.SegmentKind
import com.runner.academy.data.TrainingIcon
import com.runner.academy.data.TrainingPlan
import com.runner.academy.data.TrainingPlanDay
import com.runner.academy.data.WorkoutTemplate
import com.runner.academy.data.WorkoutTemplateSegment
import com.runner.academy.data.WorkoutType
import com.runner.academy.data.parseTrainingIcon

/**
 * Combined JSON backup for base workouts (templates) and training plans.
 * Plan days reference templates by export-local [TemplateDto.id]; on import
 * IDs are remapped to newly inserted rows.
 */
object TrainingPlanBackupFormat {

    const val FORMAT_VERSION = 1

    data class SegmentDto(
        val sortOrder: Int = 0,
        val kind: String = SegmentKind.WORK.name,
        val title: String = "",
        val goalType: String = SegmentGoalType.DURATION.name,
        val durationMs: Long? = null,
        val distanceMeters: Float? = null,
        val targetPaceMinPerKm: Float? = null
    )

    data class TemplateDto(
        val id: Long = 0,
        val name: String = "",
        val workoutType: String = WorkoutType.INTERVAL_TRAINING.name,
        val iconKey: String = TrainingIcon.INTERVAL.name,
        val notes: String? = null,
        val createdAt: Long = 0L,
        val updatedAt: Long = 0L,
        val segments: List<SegmentDto> = emptyList()
    )

    data class PlanDayDto(
        val dayIndex: Int = 0,
        /** Export-local template id; null = rest. */
        val templateId: Long? = null
    )

    data class PlanDto(
        val id: Long = 0,
        val name: String = "",
        val durationDays: Int = 1,
        val iconKey: String = TrainingIcon.PLAN.name,
        val notes: String? = null,
        val createdAt: Long = 0L,
        val updatedAt: Long = 0L,
        val days: List<PlanDayDto> = emptyList()
    )

    data class TrainingPlansBackupFile(
        val formatVersion: Int = FORMAT_VERSION,
        val exportedAt: String? = null,
        val packageName: String? = null,
        val templateCount: Int = 0,
        val planCount: Int = 0,
        val templates: List<TemplateDto> = emptyList(),
        val plans: List<PlanDto> = emptyList()
    )

    data class ParsedBackup(
        val templates: List<Pair<Long, WorkoutTemplateWithSegmentsExport>>,
        val plans: List<Pair<Long, PlanWithDaysExport>>
    )

    data class WorkoutTemplateWithSegmentsExport(
        val template: WorkoutTemplate,
        val segments: List<WorkoutTemplateSegment>
    )

    data class PlanWithDaysExport(
        val plan: TrainingPlan,
        /** Days with export-local template ids (to remap). */
        val days: List<PlanDayDto>
    )

    data class ImportResult(
        val templatesImported: Int,
        val plansImported: Int
    )

    private val gson: Gson = GsonBuilder().disableHtmlEscaping().create()

    fun toBackupJson(
        templates: List<WorkoutTemplateWithSegmentsExport>,
        plans: List<PlanWithDaysExport>,
        packageName: String
    ): String {
        val payload = TrainingPlansBackupFile(
            formatVersion = FORMAT_VERSION,
            exportedAt = java.time.Instant.now().toString(),
            packageName = packageName,
            templateCount = templates.size,
            planCount = plans.size,
            templates = templates.map { it.toDto() },
            plans = plans.map { it.toDto() }
        )
        return gson.toJson(payload)
    }

    fun getBackupJsonFileName(): String {
        val stamp = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss")
            .withZone(java.time.ZoneOffset.UTC)
            .format(java.time.Instant.now())
        return "runner_training_plans_backup_$stamp.json"
    }

    fun parseBackupJson(json: String): ParsedBackup {
        val root = try {
            JsonParser.parseString(json).asJsonObject
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid training plans backup JSON", e)
        }

        val templatesEl = root.get("templates")
        val plansEl = root.get("plans")
        val templatesArray = when {
            templatesEl == null || templatesEl.isJsonNull -> JsonArray()
            templatesEl.isJsonArray -> templatesEl.asJsonArray
            else -> throw IllegalArgumentException("Backup templates must be an array")
        }
        val plansArray = when {
            plansEl == null || plansEl.isJsonNull -> JsonArray()
            plansEl.isJsonArray -> plansEl.asJsonArray
            else -> throw IllegalArgumentException("Backup plans must be an array")
        }

        if (templatesArray.isEmpty && plansArray.isEmpty) {
            throw IllegalArgumentException("Backup contains no templates or plans")
        }

        val templates = templatesArray.mapIndexed { index, el ->
            if (!el.isJsonObject) {
                throw IllegalArgumentException("Template at index $index is not an object")
            }
            el.asJsonObject.toTemplateExport()
        }
        val plans = plansArray.mapIndexed { index, el ->
            if (!el.isJsonObject) {
                throw IllegalArgumentException("Plan at index $index is not an object")
            }
            el.asJsonObject.toPlanExport()
        }
        return ParsedBackup(templates, plans)
    }

    private fun WorkoutTemplateWithSegmentsExport.toDto() = TemplateDto(
        id = template.id,
        name = template.name,
        workoutType = template.workoutType.name,
        iconKey = template.iconKey,
        notes = template.notes,
        createdAt = template.createdAt,
        updatedAt = template.updatedAt,
        segments = segments.sortedBy { it.sortOrder }.map { seg ->
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
    )

    private fun PlanWithDaysExport.toDto() = PlanDto(
        id = plan.id,
        name = plan.name,
        durationDays = plan.durationDays,
        iconKey = plan.iconKey,
        notes = plan.notes,
        createdAt = plan.createdAt,
        updatedAt = plan.updatedAt,
        days = days
    )

    private fun JsonObject.toTemplateExport(): Pair<Long, WorkoutTemplateWithSegmentsExport> {
        val exportId = longOr("id", 0L)
        val type = WorkoutType.entries.find {
            it.name.equals(stringOr("workoutType", WorkoutType.INTERVAL_TRAINING.name), true)
        } ?: WorkoutType.INTERVAL_TRAINING
        val icon = parseTrainingIcon(
            stringOrNull("iconKey"),
            TrainingIcon.INTERVAL
        )
        val now = System.currentTimeMillis()
        val template = WorkoutTemplate(
            id = 0,
            name = stringOr("name", "").ifBlank { "Template" },
            workoutType = type,
            iconKey = icon.name,
            notes = stringOrNull("notes"),
            createdAt = longOr("createdAt", now),
            updatedAt = longOr("updatedAt", now)
        )
        val segmentsEl = get("segments")
        val segments = if (segmentsEl != null && segmentsEl.isJsonArray) {
            segmentsEl.asJsonArray.mapIndexed { index, el ->
                if (!el.isJsonObject) {
                    throw IllegalArgumentException("Segment at index $index is not an object")
                }
                el.asJsonObject.toSegment(index)
            }
        } else {
            emptyList()
        }
        return exportId to WorkoutTemplateWithSegmentsExport(template, segments)
    }

    private fun JsonObject.toSegment(fallbackOrder: Int): WorkoutTemplateSegment {
        val kind = SegmentKind.entries.find {
            it.name.equals(stringOr("kind", SegmentKind.WORK.name), true)
        } ?: SegmentKind.WORK
        val goal = SegmentGoalType.entries.find {
            it.name.equals(stringOr("goalType", SegmentGoalType.DURATION.name), true)
        } ?: SegmentGoalType.DURATION
        return WorkoutTemplateSegment(
            id = 0,
            templateId = 0,
            sortOrder = longOr("sortOrder", fallbackOrder.toLong()).toInt(),
            kind = kind,
            title = stringOr("title", kind.name),
            goalType = goal,
            durationMs = longOrNull("durationMs"),
            distanceMeters = floatOrNull("distanceMeters"),
            targetPaceMinPerKm = floatOrNull("targetPaceMinPerKm")
        )
    }

    private fun JsonObject.toPlanExport(): Pair<Long, PlanWithDaysExport> {
        val exportId = longOr("id", 0L)
        val now = System.currentTimeMillis()
        val icon = parseTrainingIcon(stringOrNull("iconKey"), TrainingIcon.PLAN)
        val plan = TrainingPlan(
            id = 0,
            name = stringOr("name", "").ifBlank { "Plan" },
            durationDays = longOr("durationDays", 1L).toInt().coerceAtLeast(1),
            iconKey = icon.name,
            notes = stringOrNull("notes"),
            createdAt = longOr("createdAt", now),
            updatedAt = longOr("updatedAt", now)
        )
        val daysEl = get("days")
        val days = if (daysEl != null && daysEl.isJsonArray) {
            daysEl.asJsonArray.mapIndexed { index, el ->
                if (!el.isJsonObject) {
                    throw IllegalArgumentException("Plan day at index $index is not an object")
                }
                val obj = el.asJsonObject
                PlanDayDto(
                    dayIndex = obj.longOr("dayIndex", index.toLong()).toInt(),
                    templateId = obj.longOrNull("templateId")
                )
            }
        } else {
            emptyList()
        }
        return exportId to PlanWithDaysExport(plan, days)
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
}
