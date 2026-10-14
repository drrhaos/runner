package com.runner.academy.util

import android.content.Context
import com.runner.academy.voice.SummaryEvery
import com.runner.academy.voice.SummaryMetric
import com.runner.academy.voice.VoiceProfile
import com.runner.academy.voice.VoiceSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The voice settings in `runner_preferences`. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class UserPreferencesVoiceTest {

    private lateinit var context: Context
    private lateinit var prefs: UserPreferences

    private val custom = VoiceSettings(
        enabled = true,
        free = VoiceProfile(SummaryEvery.MIN_10, setOf(SummaryMetric.TIME, SummaryMetric.SPLIT_HR), true, true),
        intervals = VoiceProfile(SummaryEvery.KM_2, setOf(SummaryMetric.DISTANCE), segmentChange = false, targetCheck = false),
        paceToleranceSec = 20
    )

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("runner_preferences", Context.MODE_PRIVATE).edit().clear().commit()
        prefs = UserPreferences(context)
    }

    @Test
    fun `clean preferences read as the defaults`() {
        assertEquals(VoiceSettings.DEFAULT, prefs.voiceSettings())
    }

    @Test
    fun `the old voice switch is the master switch of the new settings`() {
        prefs.voiceFeedback = true

        val settings = prefs.voiceSettings()
        assertTrue(settings.enabled)
        assertEquals(VoiceSettings.DEFAULT.free, settings.free)
    }

    @Test
    fun `settings are saved and read back, the master switch shared with the old property`() {
        prefs.setVoiceSettings(custom)

        assertEquals(custom, UserPreferences(context).voiceSettings())
        assertTrue(prefs.voiceFeedback)
        val raw = context.getSharedPreferences("runner_preferences", Context.MODE_PRIVATE)
        assertEquals("time,split_hr", raw.getString("voice_free_metrics", null))
    }

    @Test
    fun `a value of another type reads as the default`() {
        context.getSharedPreferences("runner_preferences", Context.MODE_PRIVATE).edit()
            .putString("voice_pace_tolerance_sec", "15")
            .putInt("voice_free_summary", 3)
            .commit()

        assertEquals(VoiceSettings.DEFAULT, prefs.voiceSettings())
    }

    @Test
    fun `reset to defaults resets the voice and keeps the stride model`() {
        prefs.saveStrideModel(StrideModel.fromHeight(180f))
        val model = prefs.loadStrideModel().serialize()
        prefs.setVoiceSettings(custom)

        prefs.resetToDefaults()

        assertEquals(VoiceSettings.DEFAULT, prefs.voiceSettings())
        assertEquals(model, prefs.loadStrideModel().serialize())
    }
}
