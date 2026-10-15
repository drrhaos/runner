package com.runner.academy.voice

import com.runner.academy.data.WorkoutTemplateSegment

/**
 * What the voice says, as data: tests compare lines, not strings; [VoiceLineText] renders them.
 */
sealed interface VoiceLine {

    /** The periodic summary, values included, in the order of [SummaryMetric]. */
    data class Summary(val parts: List<SummaryPart>) : VoiceLine

    /** "In 30 seconds: [segment]", with its goal and its target (a pace or a zone). */
    data class SegmentUpcoming(val segment: WorkoutTemplateSegment) : VoiceLine

    /** A segment starts: the beep only, outside the speech queue. */
    object SegmentStart : VoiceLine {
        override fun toString() = "SegmentStart"
    }

    /**
     * "Speed up, pace 4:52" / "Above zone, heart rate 172": the segment's target is missed.
     * Never queued: dropped when anything else is said or speech plays. [pace] (minutes per km)
     * for a pace verdict, [bpm] for a zone one; without it the word alone.
     */
    data class TargetHint(val verdict: TargetVerdict, val pace: Float?, val bpm: Int?) : VoiceLine

    /** An event of the GPS, the heart-rate sensor or the auto-pause; spoken at once. */
    data class Status(val kind: StatusKind) : VoiceLine
}

/** One value of a [VoiceLine.Summary]. */
sealed interface SummaryPart {
    data class Distance(val meters: Float) : SummaryPart
    data class Time(val movingMs: Long) : SummaryPart
    data class AvgPace(val minPerKm: Float) : SummaryPart
    /** The pace since the previous summary, over the stretch [span] says. */
    data class SplitPace(val minPerKm: Float, val span: SplitSpan) : SummaryPart
    /** The average heart rate since the previous summary; [span] as in [SplitPace]. */
    data class SplitHr(val bpm: Int, val span: SplitSpan) : SummaryPart
    data class CurrentHr(val bpm: Int) : SummaryPart
}

/** What stretch a split part really covered, as the summary clock knows it. */
sealed interface SplitSpan {
    /** One whole interval: "the last kilometre". */
    data class Every(val every: SummaryEvery) : SplitSpan

    /** No summary has played yet and the interval was changed (or switched on) mid-run. */
    object SinceStart : SplitSpan {
        override fun toString() = "SinceStart"
    }

    /** A partial stretch: after a restore or a change of the interval. */
    object SinceLastSummary : SplitSpan {
        override fun toString() = "SinceLastSummary"
    }
}

enum class StatusKind {
    GPS_LOST, GPS_UNRELIABLE, GPS_RECOVERED,
    HR_LOST, HR_CONNECTED,
    AUTO_PAUSE, AUTO_RESUME
}
