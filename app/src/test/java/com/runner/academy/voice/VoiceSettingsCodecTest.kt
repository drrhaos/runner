package com.runner.academy.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceSettingsCodecTest {

    @Test
    fun `clean preferences give the defaults`() {
        val settings = VoiceSettingsCodec.decode(emptyMap<String, Any?>())

        assertEquals(VoiceSettings.DEFAULT, settings)
        assertFalse(settings.enabled)
        assertEquals(SummaryEvery.KM_1, settings.free.summary)
        assertEquals(setOf(SummaryMetric.DISTANCE, SummaryMetric.TIME, SummaryMetric.AVG_PACE), settings.free.metrics)
        assertEquals(SummaryEvery.OFF, settings.intervals.summary)
        assertEquals(settings.free.metrics, settings.intervals.metrics)
        assertTrue(settings.intervals.segmentChange)
        assertTrue(settings.intervals.targetCheck)
        assertEquals(10, settings.paceToleranceSec)
    }

    @Test
    fun `the old voice switch alone turns the voice on with today's kilometre summary`() {
        val settings = VoiceSettingsCodec.decode(mapOf(VoiceSettingsCodec.KEY_ENABLED to true))

        assertTrue(settings.enabled)
        assertEquals(VoiceSettings.DEFAULT.free, settings.free)
        assertEquals(SummaryEvery.KM_1, settings.profileFor(hasSegments = false).activeSummary)
    }

    @Test
    fun `unknown metrics are dropped and the phrase keeps the enum order`() {
        val settings = VoiceSettingsCodec.decode(mapOf(VoiceSettingsCodec.KEY_FREE_METRICS to "time,bogus,distance"))

        assertEquals(listOf(SummaryMetric.DISTANCE, SummaryMetric.TIME), settings.free.orderedMetrics)
    }

    @Test
    fun `an empty metric set means no summary`() {
        val settings = VoiceSettingsCodec.decode(
            mapOf(VoiceSettingsCodec.KEY_FREE_SUMMARY to "km_1", VoiceSettingsCodec.KEY_FREE_METRICS to "")
        )

        assertEquals(SummaryEvery.KM_1, settings.free.summary)
        assertEquals(SummaryEvery.OFF, settings.free.activeSummary)
    }

    @Test
    fun `an unknown summary interval or tolerance falls back to the default`() {
        val settings = VoiceSettingsCodec.decode(
            mapOf(
                VoiceSettingsCodec.KEY_FREE_SUMMARY to "km_3",
                VoiceSettingsCodec.KEY_INTERVALS_SUMMARY to null,
                VoiceSettingsCodec.KEY_PACE_TOLERANCE_SEC to 7,
                VoiceSettingsCodec.KEY_ENABLED to "yes"
            )
        )

        assertEquals(SummaryEvery.KM_1, settings.free.summary)
        assertEquals(SummaryEvery.OFF, settings.intervals.summary)
        assertEquals(10, settings.paceToleranceSec)
        assertFalse(settings.enabled)
    }

    @Test
    fun `encode and decode round trip`() {
        val settings = VoiceSettings(
            enabled = true,
            free = VoiceProfile(SummaryEvery.MIN_5, setOf(SummaryMetric.SPLIT_PACE, SummaryMetric.CURRENT_HR), false, false),
            intervals = VoiceProfile(SummaryEvery.KM_0_5, setOf(SummaryMetric.SPLIT_HR), segmentChange = false, targetCheck = true),
            paceToleranceSec = 15
        )

        val wire = VoiceSettingsCodec.encode(settings)

        assertEquals("split_pace,current_hr", wire[VoiceSettingsCodec.KEY_FREE_METRICS])
        assertEquals("min_5", wire[VoiceSettingsCodec.KEY_FREE_SUMMARY])
        // The free profile has no segments: its switches are not stored
        assertEquals(settings.copy(free = settings.free.copy(segmentChange = true, targetCheck = true)),
            VoiceSettingsCodec.decode(wire))
    }

    @Test
    fun `every summary interval has a unique wire value and one unit`() {
        assertEquals(SummaryEvery.entries.size, SummaryEvery.entries.map { it.wire }.toSet().size)
        SummaryEvery.entries.filter { it != SummaryEvery.OFF }.forEach {
            assertTrue(it.name, (it.meters == null) != (it.minutes == null))
        }
        assertEquals(listOf(5, 10, 15, 20), VoiceSettings.PACE_TOLERANCES_SEC)
    }

    @Test
    fun `the profile follows the segments`() {
        val settings = VoiceSettings.DEFAULT
        assertEquals(settings.intervals, settings.profileFor(hasSegments = true))
        assertEquals(settings.free, settings.profileFor(hasSegments = false))
    }
}
