package com.runner.academy.voice

/** How often the summary speaks: by distance, by moving time, or never. */
enum class SummaryEvery(val wire: String, val meters: Int?, val minutes: Int?) {
    OFF("off", null, null),
    KM_0_5("km_0.5", 500, null),
    KM_1("km_1", 1000, null),
    KM_2("km_2", 2000, null),
    KM_5("km_5", 5000, null),
    MIN_1("min_1", null, 1),
    MIN_5("min_5", null, 5),
    MIN_10("min_10", null, 10),
    MIN_15("min_15", null, 15);

    companion object {
        fun ofWire(wire: String?): SummaryEvery? = entries.firstOrNull { it.wire == wire }
    }
}

/** What the summary says; the enum order is the order in the phrase. */
enum class SummaryMetric(val wire: String) {
    DISTANCE("distance"),
    TIME("time"),
    AVG_PACE("avg_pace"),
    /** The pace since the previous summary. */
    SPLIT_PACE("split_pace"),
    /** The average heart rate since the previous summary. */
    SPLIT_HR("split_hr"),
    CURRENT_HR("current_hr");

    companion object {
        fun ofWire(wire: String): SummaryMetric? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * One of the two voice profiles. [segmentChange] (the beep and the 30 s warning) and
 * [targetCheck] only mean something in the interval profile: a free run has no segments.
 */
data class VoiceProfile(
    val summary: SummaryEvery,
    val metrics: Set<SummaryMetric>,
    val segmentChange: Boolean,
    val targetCheck: Boolean
) {
    /** [summary], or [SummaryEvery.OFF] when there is nothing to say. */
    val activeSummary: SummaryEvery get() = if (metrics.isEmpty()) SummaryEvery.OFF else summary

    /** [metrics] in the order of the phrase. */
    val orderedMetrics: List<SummaryMetric> get() = SummaryMetric.entries.filter { it in metrics }
}

/**
 * The voice settings. [enabled] is the master switch (GPS, sensor and auto-pause lines speak
 * under it in both profiles); the profile is picked by whether the workout has segments.
 */
data class VoiceSettings(
    val enabled: Boolean,
    val free: VoiceProfile,
    val intervals: VoiceProfile,
    val paceToleranceSec: Int
) {
    fun profileFor(hasSegments: Boolean): VoiceProfile = if (hasSegments) intervals else free

    companion object {
        val PACE_TOLERANCES_SEC = listOf(5, 10, 15, 20)
        const val DEFAULT_PACE_TOLERANCE_SEC = 10

        /** The kilometre summary of distance, time and average pace. */
        val DEFAULT_METRICS = setOf(SummaryMetric.DISTANCE, SummaryMetric.TIME, SummaryMetric.AVG_PACE)

        val DEFAULT = VoiceSettings(
            enabled = false,
            free = VoiceProfile(SummaryEvery.KM_1, DEFAULT_METRICS, segmentChange = true, targetCheck = true),
            intervals = VoiceProfile(SummaryEvery.OFF, DEFAULT_METRICS, segmentChange = true, targetCheck = true),
            paceToleranceSec = DEFAULT_PACE_TOLERANCE_SEC
        )
    }
}
