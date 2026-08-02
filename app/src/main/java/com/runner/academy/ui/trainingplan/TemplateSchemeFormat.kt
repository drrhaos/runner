package com.runner.academy.ui.trainingplan

import android.content.Context
import com.runner.academy.R
import com.runner.academy.data.SegmentGoalType
import com.runner.academy.data.WorkoutTemplateSegment
import com.runner.academy.data.localizedTitle
import com.runner.academy.util.FormatUtils
import com.runner.academy.util.SpeedPaceCalculator

/** Compact one-line scheme: Warm-up 10:00 → Interval 1 3:00 → … */
fun formatTemplateScheme(
    context: Context,
    segments: List<WorkoutTemplateSegment>
): String {
    if (segments.isEmpty()) {
        return context.getString(R.string.template_scheme_empty)
    }
    return segments.joinToString(" → ") { segment ->
        formatSegmentParams(context, segment)
    }
}

/** Multiline bullet list of segments with goals and optional pace. */
fun formatTemplateSchemeDetailed(
    context: Context,
    segments: List<WorkoutTemplateSegment>
): String {
    if (segments.isEmpty()) {
        return context.getString(R.string.template_scheme_empty)
    }
    return segments.joinToString("\n") { segment ->
        "• ${formatSegmentParams(context, segment)}"
    }
}

/** Single segment params: "Warm-up 10:00 @ 6:00". */
fun formatSegmentParams(
    context: Context,
    segment: WorkoutTemplateSegment
): String {
    val goal = when (segment.goalType) {
        SegmentGoalType.DURATION -> segment.durationMs?.let { FormatUtils.formatTime(it) } ?: "—"
        SegmentGoalType.DISTANCE -> segment.distanceMeters?.let { meters ->
            FormatUtils.formatDistanceMeters(meters, context)
        } ?: "—"
    }
    val pace = segment.targetPaceMinPerKm
        ?.takeIf { it > 0f }
        ?.let { " @ ${SpeedPaceCalculator.formatPaceMmSs(it)}" }
        .orEmpty()
    return "${segment.localizedTitle(context)} $goal$pace"
}
