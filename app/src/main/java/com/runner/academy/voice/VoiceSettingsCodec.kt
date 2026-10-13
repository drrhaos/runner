package com.runner.academy.voice

/**
 * [VoiceSettings] to and from the preference keys. One key per field, so new fields need no
 * version and the values read in a debugger; metric sets are CSV of [SummaryMetric.wire] (not a
 * string set: those are mutable and unordered). Missing or unknown values take the default.
 */
object VoiceSettingsCodec {

    /** The master switch, the same key as before release 4: no migration. */
    const val KEY_ENABLED = "voice_feedback"
    const val KEY_FREE_SUMMARY = "voice_free_summary"
    const val KEY_FREE_METRICS = "voice_free_metrics"
    const val KEY_INTERVALS_SUMMARY = "voice_intervals_summary"
    const val KEY_INTERVALS_METRICS = "voice_intervals_metrics"
    const val KEY_INTERVALS_SEGMENT_CHANGE = "voice_intervals_segment_change"
    const val KEY_INTERVALS_TARGET_CHECK = "voice_intervals_target_check"
    const val KEY_PACE_TOLERANCE_SEC = "voice_pace_tolerance_sec"

    fun decode(values: Map<String, *>): VoiceSettings {
        val default = VoiceSettings.DEFAULT
        return VoiceSettings(
            enabled = values[KEY_ENABLED] as? Boolean ?: default.enabled,
            free = default.free.copy(
                summary = summaryOf(values[KEY_FREE_SUMMARY]) ?: default.free.summary,
                metrics = metricsOf(values[KEY_FREE_METRICS]) ?: default.free.metrics
            ),
            intervals = VoiceProfile(
                summary = summaryOf(values[KEY_INTERVALS_SUMMARY]) ?: default.intervals.summary,
                metrics = metricsOf(values[KEY_INTERVALS_METRICS]) ?: default.intervals.metrics,
                segmentChange = values[KEY_INTERVALS_SEGMENT_CHANGE] as? Boolean ?: default.intervals.segmentChange,
                targetCheck = values[KEY_INTERVALS_TARGET_CHECK] as? Boolean ?: default.intervals.targetCheck
            ),
            paceToleranceSec = (values[KEY_PACE_TOLERANCE_SEC] as? Int)
                ?.takeIf { it in VoiceSettings.PACE_TOLERANCES_SEC }
                ?: default.paceToleranceSec
        )
    }

    /** The values to store; Boolean, String or Int each. */
    fun encode(settings: VoiceSettings): Map<String, Any> = mapOf(
        KEY_ENABLED to settings.enabled,
        KEY_FREE_SUMMARY to settings.free.summary.wire,
        KEY_FREE_METRICS to metricsWire(settings.free.metrics),
        KEY_INTERVALS_SUMMARY to settings.intervals.summary.wire,
        KEY_INTERVALS_METRICS to metricsWire(settings.intervals.metrics),
        KEY_INTERVALS_SEGMENT_CHANGE to settings.intervals.segmentChange,
        KEY_INTERVALS_TARGET_CHECK to settings.intervals.targetCheck,
        KEY_PACE_TOLERANCE_SEC to settings.paceToleranceSec
    )

    private fun summaryOf(value: Any?): SummaryEvery? = SummaryEvery.ofWire(value as? String)

    /** Null when not stored; an empty string is an empty set. */
    private fun metricsOf(value: Any?): Set<SummaryMetric>? {
        val csv = value as? String ?: return null
        return csv.split(',').mapNotNull { SummaryMetric.ofWire(it.trim()) }.toSet()
    }

    private fun metricsWire(metrics: Set<SummaryMetric>): String =
        SummaryMetric.entries.filter { it in metrics }.joinToString(",") { it.wire }
}
