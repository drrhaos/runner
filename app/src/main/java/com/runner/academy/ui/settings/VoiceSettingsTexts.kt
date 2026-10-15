package com.runner.academy.ui.settings

import androidx.annotation.StringRes
import com.runner.academy.R
import com.runner.academy.voice.SummaryEvery
import com.runner.academy.voice.SummaryMetric
import com.runner.academy.voice.VoiceProfile
import com.runner.academy.voice.VoiceSettings

/** The names of the voice settings' values. */
object VoiceSettingsTexts {

    /** "Every 1 km": the summary row's value and the dialog's items. */
    @StringRes
    fun every(every: SummaryEvery): Int = when (every) {
        SummaryEvery.OFF -> R.string.voice_every_off
        SummaryEvery.KM_0_5 -> R.string.voice_every_km_0_5
        SummaryEvery.KM_1 -> R.string.voice_every_km_1
        SummaryEvery.KM_2 -> R.string.voice_every_km_2
        SummaryEvery.KM_5 -> R.string.voice_every_km_5
        SummaryEvery.MIN_1 -> R.string.voice_every_min_1
        SummaryEvery.MIN_5 -> R.string.voice_every_min_5
        SummaryEvery.MIN_10 -> R.string.voice_every_min_10
        SummaryEvery.MIN_15 -> R.string.voice_every_min_15
    }

    /** "every 1 km" inside the Settings row's subtitle; null for no summary. */
    @StringRes
    fun everyShort(every: SummaryEvery): Int? = when (every) {
        SummaryEvery.OFF -> null
        SummaryEvery.KM_0_5 -> R.string.voice_every_short_km_0_5
        SummaryEvery.KM_1 -> R.string.voice_every_short_km_1
        SummaryEvery.KM_2 -> R.string.voice_every_short_km_2
        SummaryEvery.KM_5 -> R.string.voice_every_short_km_5
        SummaryEvery.MIN_1 -> R.string.voice_every_short_min_1
        SummaryEvery.MIN_5 -> R.string.voice_every_short_min_5
        SummaryEvery.MIN_10 -> R.string.voice_every_short_min_10
        SummaryEvery.MIN_15 -> R.string.voice_every_short_min_15
    }

    /** The metrics dialog's item. */
    @StringRes
    fun metric(metric: SummaryMetric): Int = when (metric) {
        SummaryMetric.DISTANCE -> R.string.voice_metric_distance
        SummaryMetric.TIME -> R.string.voice_metric_time
        SummaryMetric.AVG_PACE -> R.string.voice_metric_avg_pace
        SummaryMetric.SPLIT_PACE -> R.string.voice_metric_split_pace
        SummaryMetric.SPLIT_HR -> R.string.voice_metric_split_hr
        SummaryMetric.CURRENT_HR -> R.string.voice_metric_current_hr
    }

    /**
     * The metrics row's subtitle, to be joined with ", ": lower case in the
     * order of the phrase. Own keys, not `lowercase()`: that would break abbreviations in Russian.
     */
    fun metricsLower(metrics: Set<SummaryMetric>): List<Int> =
        SummaryMetric.entries.filter { it in metrics }.map { metricLower(it) }

    @StringRes
    private fun metricLower(metric: SummaryMetric): Int = when (metric) {
        SummaryMetric.DISTANCE -> R.string.voice_metric_distance_lower
        SummaryMetric.TIME -> R.string.voice_metric_time_lower
        SummaryMetric.AVG_PACE -> R.string.voice_metric_avg_pace_lower
        SummaryMetric.SPLIT_PACE -> R.string.voice_metric_split_pace_lower
        SummaryMetric.SPLIT_HR -> R.string.voice_metric_split_hr_lower
        SummaryMetric.CURRENT_HR -> R.string.voice_metric_current_hr_lower
    }
}

/**
 * The subtitle of the Voice coaching row in Settings: "On · summary every 1 km".
 *
 * @param every the argument of [text] (`voice_every_short_*`); null when [text] takes none.
 */
data class VoiceRowSummary(@StringRes val text: Int, @StringRes val every: Int?) {

    companion object {
        /** Off, or on with the free run's summary: the profile of most runs. */
        fun of(settings: VoiceSettings): VoiceRowSummary {
            if (!settings.enabled) return VoiceRowSummary(R.string.settings_voice_summary_off, null)
            val every = VoiceSettingsTexts.everyShort(settings.free.activeSummary)
                ?: return VoiceRowSummary(R.string.settings_voice_summary_on_no_summary, null)
            return VoiceRowSummary(R.string.settings_voice_summary_on, every)
        }
    }
}

/** What the Voice coaching screen shows for one profile. */
data class VoiceProfileRows(
    /** The summary as it works: off when there is nothing to say. */
    val summary: SummaryEvery,
    val metrics: Set<SummaryMetric>,
    val segmentChange: Boolean,
    val targetCheck: Boolean,
    /** All rows of the profile: off with the master switch. */
    val rowsEnabled: Boolean,
    /** "What to say": also off while the summary is. */
    val metricsEnabled: Boolean,
    /** Heart rate is asked for and there is no monitor to give it. */
    val heartRateSkipped: Boolean
)

/** The Voice coaching screen, from the settings and what else it depends on. */
data class VoiceScreen(
    val enabled: Boolean,
    val free: VoiceProfileRows,
    val intervals: VoiceProfileRows,
    val paceToleranceSec: Int,
    /** The tolerance also drives the segment bar, which works without the voice. */
    val toleranceEnabled: Boolean,
    /** Miles are chosen but the voice speaks kilometres. */
    val kilometresNote: Boolean
) {
    companion object {
        private val HEART_RATE_METRICS = setOf(SummaryMetric.SPLIT_HR, SummaryMetric.CURRENT_HR)

        fun of(settings: VoiceSettings, hasHeartRateSensor: Boolean, unitSystem: String): VoiceScreen {
            fun rows(profile: VoiceProfile): VoiceProfileRows {
                val summary = profile.activeSummary
                return VoiceProfileRows(
                    summary = summary,
                    metrics = profile.metrics,
                    segmentChange = profile.segmentChange,
                    targetCheck = profile.targetCheck,
                    rowsEnabled = settings.enabled,
                    metricsEnabled = settings.enabled && summary != SummaryEvery.OFF,
                    heartRateSkipped = !hasHeartRateSensor && profile.metrics.any { it in HEART_RATE_METRICS }
                )
            }
            return VoiceScreen(
                enabled = settings.enabled,
                free = rows(settings.free),
                intervals = rows(settings.intervals),
                paceToleranceSec = settings.paceToleranceSec,
                toleranceEnabled = true,
                kilometresNote = unitSystem == UNIT_SYSTEM_IMPERIAL
            )
        }

        private const val UNIT_SYSTEM_IMPERIAL = "imperial"
    }
}
