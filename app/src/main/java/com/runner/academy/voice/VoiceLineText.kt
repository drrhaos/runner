package com.runner.academy.voice

import android.content.Context
import androidx.annotation.StringRes
import com.runner.academy.R
import com.runner.academy.data.SegmentGoalType
import com.runner.academy.data.WorkoutTemplateSegment
import com.runner.academy.data.localizedTitle
import com.runner.academy.ui.workout.MetricSpeech
import com.runner.academy.util.FormatUtils

/**
 * The words of a [VoiceLine] in the app's language: the parts of one phrase, said with a short
 * pause between them. The voice is always metric. [VoiceLine.SegmentStart] has no words (a beep).
 */
object VoiceLineText {

    fun render(line: VoiceLine, context: Context): List<String> = when (line) {
        is VoiceLine.Summary -> line.parts.map { part(it, context) }
        is VoiceLine.SegmentUpcoming -> upcoming(line.segment, context)
        is VoiceLine.Status -> listOf(context.getString(statusText(line.kind)))
        VoiceLine.SegmentStart -> emptyList()
    }

    private fun part(part: SummaryPart, context: Context): String = when (part) {
        is SummaryPart.Distance ->
            context.getString(R.string.voice_summary_distance, MetricSpeech.distance(context, part.meters / 1_000f))
        is SummaryPart.Time ->
            context.getString(R.string.voice_summary_time, FormatUtils.formatTimeForTTS(part.movingMs, context))
        is SummaryPart.AvgPace ->
            context.getString(R.string.voice_summary_avg_pace, FormatUtils.formatPaceForTTS(part.minPerKm, context))
        is SummaryPart.SplitPace -> {
            val pace = FormatUtils.formatPaceForTTS(part.minPerKm, context)
            when (val span = part.span) {
                is SplitSpan.Every -> spanText(span.every)
                    ?.let { context.getString(R.string.voice_summary_split_pace, context.getString(it), pace) }
                    ?: context.getString(R.string.voice_summary_split_pace_since, pace)
                SplitSpan.SinceStart -> context.getString(R.string.voice_summary_split_pace_since_start, pace)
                SplitSpan.SinceLastSummary -> context.getString(R.string.voice_summary_split_pace_since, pace)
            }
        }
        is SummaryPart.SplitHr -> when (val span = part.span) {
            is SplitSpan.Every -> spanText(span.every)
                ?.let { context.getString(R.string.voice_summary_split_hr, context.getString(it), part.bpm) }
                ?: context.getString(R.string.voice_summary_split_hr_since, part.bpm)
            SplitSpan.SinceStart -> context.getString(R.string.voice_summary_split_hr_since_start, part.bpm)
            SplitSpan.SinceLastSummary -> context.getString(R.string.voice_summary_split_hr_since, part.bpm)
        }
        is SummaryPart.CurrentHr -> context.getString(R.string.voice_summary_current_hr, part.bpm)
    }

    /** "In 30 seconds: [title]", then the goal, then the target pace if any. */
    private fun upcoming(segment: WorkoutTemplateSegment, context: Context): List<String> = buildList {
        add(context.getString(R.string.voice_interval_upcoming_in_30s, segment.localizedTitle(context)))
        when (segment.goalType) {
            SegmentGoalType.DURATION -> segment.durationMs?.takeIf { it > 0 }?.let { ms ->
                add(context.getString(R.string.voice_interval_goal_duration, FormatUtils.formatTimeForTTS(ms, context)))
            }
            SegmentGoalType.DISTANCE -> segment.distanceMeters?.takeIf { it > 0f }?.let { meters ->
                add(context.getString(R.string.voice_interval_goal_distance, FormatUtils.formatDistanceMetersForTTS(meters, context)))
            }
        }
        segment.targetPaceMinPerKm?.takeIf { it > 0f }?.let { pace ->
            add(context.getString(R.string.voice_interval_goal_pace, FormatUtils.formatPaceForTTS(pace, context)))
        }
    }

    @StringRes
    private fun spanText(every: SummaryEvery): Int? = when (every) {
        SummaryEvery.OFF -> null
        SummaryEvery.KM_0_5 -> R.string.voice_span_km_0_5
        SummaryEvery.KM_1 -> R.string.voice_span_km_1
        SummaryEvery.KM_2 -> R.string.voice_span_km_2
        SummaryEvery.KM_5 -> R.string.voice_span_km_5
        SummaryEvery.MIN_1 -> R.string.voice_span_min_1
        SummaryEvery.MIN_5 -> R.string.voice_span_min_5
        SummaryEvery.MIN_10 -> R.string.voice_span_min_10
        SummaryEvery.MIN_15 -> R.string.voice_span_min_15
    }

    @StringRes
    private fun statusText(kind: StatusKind): Int = when (kind) {
        StatusKind.GPS_LOST -> R.string.voice_gps_lost
        StatusKind.GPS_UNRELIABLE -> R.string.voice_gps_unreliable
        StatusKind.GPS_RECOVERED -> R.string.voice_gps_recovered
        StatusKind.HR_LOST -> R.string.voice_hr_sensor_lost
        StatusKind.HR_CONNECTED -> R.string.voice_hr_sensor_connected
        StatusKind.AUTO_PAUSE -> R.string.voice_auto_pause
        StatusKind.AUTO_RESUME -> R.string.voice_auto_resume
    }
}
