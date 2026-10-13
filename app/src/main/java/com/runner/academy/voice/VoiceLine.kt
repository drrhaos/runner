package com.runner.academy.voice

import com.runner.academy.data.WorkoutTemplateSegment

/**
 * What the voice says, as data: tests compare lines, not strings; [VoiceLineText] renders them.
 */
sealed interface VoiceLine {

    /** The periodic summary, values included, in the order of [SummaryMetric]. */
    data class Summary(val parts: List<SummaryPart>) : VoiceLine

    /** "In 30 seconds: [segment]", with its goal and target pace. */
    data class SegmentUpcoming(val segment: WorkoutTemplateSegment) : VoiceLine

    /** A segment starts: the beep only, outside the speech queue. */
    object SegmentStart : VoiceLine {
        override fun toString() = "SegmentStart"
    }

    /** An event of the GPS, the heart-rate sensor or the auto-pause; spoken at once. */
    data class Status(val kind: StatusKind) : VoiceLine
}

/** One value of a [VoiceLine.Summary]. */
sealed interface SummaryPart {
    data class Distance(val meters: Float) : SummaryPart
    data class Time(val movingMs: Long) : SummaryPart
    data class AvgPace(val minPerKm: Float) : SummaryPart
    /**
     * The pace since the previous summary. [span]: the interval that stretch really covered
     * ("the last kilometre"); null when it is partial (after a restore or a change of the
     * interval), said as "since the last summary".
     */
    data class SplitPace(val minPerKm: Float, val span: SummaryEvery?) : SummaryPart
    /** The average heart rate since the previous summary; [span] as in [SplitPace]. */
    data class SplitHr(val bpm: Int, val span: SummaryEvery?) : SummaryPart
    data class CurrentHr(val bpm: Int) : SummaryPart
}

enum class StatusKind {
    GPS_LOST, GPS_UNRELIABLE, GPS_RECOVERED,
    HR_LOST, HR_CONNECTED,
    AUTO_PAUSE, AUTO_RESUME
}
