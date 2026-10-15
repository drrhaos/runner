package com.runner.academy.ui.settings

import com.runner.academy.R
import com.runner.academy.voice.SummaryEvery
import com.runner.academy.voice.SummaryMetric
import com.runner.academy.voice.VoiceSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceSettingsTextsTest {

    private val defaults = VoiceSettings.DEFAULT
    private val on = defaults.copy(enabled = true)

    // --- The Settings row's subtitle ---

    @Test
    fun `voice off says off whatever the summary`() {
        assertEquals(VoiceRowSummary(R.string.settings_voice_summary_off, null), VoiceRowSummary.of(defaults))
    }

    @Test
    fun `voice on names the free run summary`() {
        assertEquals(
            VoiceRowSummary(R.string.settings_voice_summary_on, R.string.voice_every_short_km_1),
            VoiceRowSummary.of(on)
        )
        assertEquals(
            VoiceRowSummary(R.string.settings_voice_summary_on, R.string.voice_every_short_min_5),
            VoiceRowSummary.of(on.copy(free = on.free.copy(summary = SummaryEvery.MIN_5)))
        )
    }

    @Test
    fun `voice on without a free run summary says so, the intervals summary does not count`() {
        val noSummary = on.copy(
            free = on.free.copy(summary = SummaryEvery.OFF),
            intervals = on.intervals.copy(summary = SummaryEvery.KM_1)
        )
        assertEquals(VoiceRowSummary(R.string.settings_voice_summary_on_no_summary, null), VoiceRowSummary.of(noSummary))
    }

    @Test
    fun `a summary with nothing to say counts as no summary`() {
        val empty = on.copy(free = on.free.copy(metrics = emptySet()))
        assertEquals(R.string.settings_voice_summary_on_no_summary, VoiceRowSummary.of(empty).text)
    }

    // --- Names ---

    @Test
    fun `every interval has a long and a short name in enum order`() {
        assertEquals(
            listOf(
                R.string.voice_every_off, R.string.voice_every_km_0_5, R.string.voice_every_km_1,
                R.string.voice_every_km_2, R.string.voice_every_km_5, R.string.voice_every_min_1,
                R.string.voice_every_min_5, R.string.voice_every_min_10, R.string.voice_every_min_15
            ),
            SummaryEvery.entries.map { VoiceSettingsTexts.every(it) }
        )
        assertNull(VoiceSettingsTexts.everyShort(SummaryEvery.OFF))
        assertEquals(R.string.voice_every_short_km_0_5, VoiceSettingsTexts.everyShort(SummaryEvery.KM_0_5))
        assertEquals(R.string.voice_every_short_min_15, VoiceSettingsTexts.everyShort(SummaryEvery.MIN_15))
    }

    @Test
    fun `the metrics subtitle is lower case in phrase order`() {
        assertEquals(
            listOf(R.string.voice_metric_distance_lower, R.string.voice_metric_avg_pace_lower, R.string.voice_metric_current_hr_lower),
            VoiceSettingsTexts.metricsLower(setOf(SummaryMetric.CURRENT_HR, SummaryMetric.AVG_PACE, SummaryMetric.DISTANCE))
        )
        assertEquals(
            listOf(
                R.string.voice_metric_distance, R.string.voice_metric_time, R.string.voice_metric_avg_pace,
                R.string.voice_metric_split_pace, R.string.voice_metric_split_hr, R.string.voice_metric_current_hr
            ),
            SummaryMetric.entries.map { VoiceSettingsTexts.metric(it) }
        )
    }

    // --- The screen ---

    @Test
    fun `voice off greys out both profiles but not the tolerance`() {
        val screen = VoiceScreen.of(defaults, hasHeartRateSensor = true, unitSystem = "metric")

        assertFalse(screen.enabled)
        assertFalse(screen.free.rowsEnabled)
        assertFalse(screen.free.metricsEnabled)
        assertFalse(screen.intervals.rowsEnabled)
        assertTrue(screen.toleranceEnabled)
    }

    @Test
    fun `voice on, a profile with its summary off greys out only its metrics`() {
        val screen = VoiceScreen.of(on, hasHeartRateSensor = true, unitSystem = "metric")

        assertTrue(screen.free.rowsEnabled)
        assertTrue(screen.free.metricsEnabled)
        assertTrue(screen.intervals.rowsEnabled)
        assertFalse(screen.intervals.metricsEnabled)
        assertEquals(SummaryEvery.OFF, screen.intervals.summary)
        assertEquals(SummaryEvery.KM_1, screen.free.summary)
    }

    @Test
    fun `a summary with nothing to say shows as off`() {
        val screen = VoiceScreen.of(on.copy(free = on.free.copy(metrics = emptySet())), true, "metric")

        assertEquals(SummaryEvery.OFF, screen.free.summary)
        assertFalse(screen.free.metricsEnabled)
    }

    @Test
    fun `heart rate in the summary without a monitor is said to be skipped`() {
        val withHr = on.copy(free = on.free.copy(metrics = setOf(SummaryMetric.TIME, SummaryMetric.SPLIT_HR)))

        assertTrue(VoiceScreen.of(withHr, hasHeartRateSensor = false, unitSystem = "metric").free.heartRateSkipped)
        assertFalse(VoiceScreen.of(withHr, hasHeartRateSensor = true, unitSystem = "metric").free.heartRateSkipped)
        assertFalse(VoiceScreen.of(on, hasHeartRateSensor = false, unitSystem = "metric").free.heartRateSkipped)
        val current = on.copy(intervals = on.intervals.copy(metrics = setOf(SummaryMetric.CURRENT_HR)))
        assertTrue(VoiceScreen.of(current, hasHeartRateSensor = false, unitSystem = "metric").intervals.heartRateSkipped)
    }

    @Test
    fun `the kilometre footnote shows with miles only`() {
        assertTrue(VoiceScreen.of(on, true, "imperial").kilometresNote)
        assertFalse(VoiceScreen.of(on, true, "metric").kilometresNote)
    }

    @Test
    fun `the screen carries the switches and the tolerance`() {
        val settings = on.copy(
            intervals = on.intervals.copy(segmentChange = false, targetCheck = true),
            paceToleranceSec = 15
        )
        val screen = VoiceScreen.of(settings, true, "metric")

        assertFalse(screen.intervals.segmentChange)
        assertTrue(screen.intervals.targetCheck)
        assertEquals(15, screen.paceToleranceSec)
        assertEquals(on.free.metrics, screen.free.metrics)
    }
}
