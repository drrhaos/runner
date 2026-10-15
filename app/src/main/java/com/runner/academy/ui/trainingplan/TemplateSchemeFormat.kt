package com.runner.academy.ui.trainingplan

import android.content.Context
import com.runner.academy.R
import com.runner.academy.data.SegmentGoalType
import com.runner.academy.data.SegmentTarget
import com.runner.academy.data.WorkoutTemplateSegment
import com.runner.academy.data.localizedTitle
import com.runner.academy.data.target
import com.runner.academy.util.FormatUtils
import com.runner.academy.util.SpeedPaceCalculator

/** Compact one-line scheme: Warm-up 10:00 → Interval 1 3:00 → … */
fun formatTemplateScheme(
    context: Context,
    segments: List<WorkoutTemplateSegment>,
    forA11y: Boolean = false
): String {
    if (segments.isEmpty()) {
        return context.getString(R.string.template_scheme_empty)
    }
    return segments.joinToString(" → ") { segment ->
        formatSegmentParams(context, segment, forA11y)
    }
}

/**
 * Multiline bullet list of segments with goals and targets, for the scheme details dialog.
 * The zone is spelled out ("zone 4"): the dialog text is what TalkBack reads, and in a
 * detailed list the word reads better than the badge.
 */
fun formatTemplateSchemeDetailed(
    context: Context,
    segments: List<WorkoutTemplateSegment>
): String {
    if (segments.isEmpty()) {
        return context.getString(R.string.template_scheme_empty)
    }
    return segments.joinToString("\n") { segment ->
        "• ${formatSegmentParams(context, segment, forA11y = true)}"
    }
}

/**
 * Single segment params: "Warm-up 10:00 @ 6:00" for a pace target, "Work 3:00 · Z4" for a zone.
 * [forA11y] spells the zone out ("zone 4") so TalkBack does not read "Z4" letter by letter.
 */
fun formatSegmentParams(
    context: Context,
    segment: WorkoutTemplateSegment,
    forA11y: Boolean = false
): String {
    val goal = when (segment.goalType) {
        SegmentGoalType.DURATION -> segment.durationMs?.let { FormatUtils.formatTime(it) } ?: "—"
        SegmentGoalType.DISTANCE -> segment.distanceMeters?.let { meters ->
            FormatUtils.formatDistanceMeters(meters, context)
        } ?: "—"
    }
    val target = when (val target = segment.target) {
        SegmentTarget.None -> ""
        is SegmentTarget.Pace -> " @ ${SpeedPaceCalculator.formatPaceMmSs(target.minPerKm)}"
        is SegmentTarget.HrZone -> {
            val zoneRes = if (forA11y) R.string.segment_target_zone_a11y else R.string.segment_target_zone_short
            " · ${context.getString(zoneRes, target.zone)}"
        }
    }
    return "${segment.localizedTitle(context)} $goal$target"
}
