package com.runner.academy.ui.settings

import android.content.Context
import com.runner.academy.sensor.PairedSensor
import com.runner.academy.sensor.SensorLink
import com.runner.academy.sensor.SensorStatus
import com.runner.academy.sensor.SensorType
import com.runner.academy.util.UserPreferences
import com.runner.academy.voice.SummaryEvery
import com.runner.academy.voice.SummaryMetric
import com.runner.academy.voice.VoiceSettings
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The voice settings in [SettingsViewModel]: every change is written through at once. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SettingsViewModelVoiceTest {

    private lateinit var context: Context
    private lateinit var prefs: UserPreferences
    private val paired = MutableStateFlow<Map<SensorType, PairedSensor>>(emptyMap())

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("runner_preferences", Context.MODE_PRIVATE).edit().clear().commit()
        prefs = UserPreferences(context)
    }

    private fun viewModel() = SettingsViewModel(context, prefs, paired, MutableStateFlow(SensorStatus(SensorLink.IDLE)))

    /** What the service would read on its next tick. */
    private val stored get() = UserPreferences(context).voiceSettings()

    @Test
    fun `the state starts from the stored settings`() {
        prefs.setVoiceSettings(VoiceSettings.DEFAULT.copy(enabled = true, paceToleranceSec = 20))

        assertEquals(stored, viewModel().settingsState.value.voice)
    }

    @Test
    fun `the master switch is written through`() {
        val vm = viewModel()

        vm.updateVoiceEnabled(true)

        assertTrue(stored.enabled)
        assertTrue(prefs.voiceFeedback)
        assertTrue(vm.settingsState.value.voice.enabled)
    }

    @Test
    fun `each profile's summary and metrics are written through`() {
        val vm = viewModel()

        vm.updateVoiceSummary(VoiceProfileKind.INTERVALS, SummaryEvery.MIN_5)
        vm.updateVoiceMetrics(VoiceProfileKind.FREE, setOf(SummaryMetric.CURRENT_HR, SummaryMetric.TIME))

        assertEquals(SummaryEvery.MIN_5, stored.intervals.summary)
        assertEquals(SummaryEvery.KM_1, stored.free.summary)
        assertEquals(setOf(SummaryMetric.TIME, SummaryMetric.CURRENT_HR), stored.free.metrics)
        assertEquals(VoiceSettings.DEFAULT_METRICS, stored.intervals.metrics)
        assertEquals(stored, vm.settingsState.value.voice)
    }

    @Test
    fun `an empty metric set is not saved`() {
        val vm = viewModel()

        vm.updateVoiceMetrics(VoiceProfileKind.FREE, emptySet())

        assertEquals(VoiceSettings.DEFAULT_METRICS, stored.free.metrics)
        assertEquals(VoiceSettings.DEFAULT_METRICS, vm.settingsState.value.voice.free.metrics)
    }

    @Test
    fun `turning a summary on with nothing to say brings the default metrics back`() {
        prefs.setVoiceSettings(VoiceSettings.DEFAULT.copy(free = VoiceSettings.DEFAULT.free.copy(metrics = emptySet())))
        val vm = viewModel()

        vm.updateVoiceSummary(VoiceProfileKind.FREE, SummaryEvery.KM_2)

        assertEquals(SummaryEvery.KM_2, stored.free.activeSummary)
        assertEquals(VoiceSettings.DEFAULT_METRICS, stored.free.metrics)
    }

    @Test
    fun `the interval switches and the tolerance are written through`() {
        val vm = viewModel()

        vm.updateVoiceSegmentChange(false)
        vm.updateVoiceTargetCheck(false)
        vm.updateVoicePaceTolerance(15)

        assertFalse(stored.intervals.segmentChange)
        assertFalse(stored.intervals.targetCheck)
        assertEquals(15, stored.paceToleranceSec)
        // The free profile has no segments: its flags are left alone
        assertEquals(VoiceSettings.DEFAULT.free, stored.free)
    }

    @Test
    fun `a tolerance outside the choices is ignored`() {
        val vm = viewModel()

        vm.updateVoicePaceTolerance(7)

        assertEquals(VoiceSettings.DEFAULT_PACE_TOLERANCE_SEC, stored.paceToleranceSec)
    }

    @Test
    fun `refreshing picks up a change made by another screen`() {
        val vm = viewModel()
        prefs.setVoiceSettings(VoiceSettings.DEFAULT.copy(enabled = true))

        vm.refreshVoice()

        assertTrue(vm.settingsState.value.voice.enabled)
    }

    @Test
    fun `reset brings the voice defaults back`() {
        val vm = viewModel()
        vm.updateVoiceEnabled(true)
        vm.updateVoiceSummary(VoiceProfileKind.FREE, SummaryEvery.OFF)

        vm.resetToDefaults()

        assertEquals(VoiceSettings.DEFAULT, vm.settingsState.value.voice)
        assertEquals(VoiceSettings.DEFAULT, stored)
    }

    @Test
    fun `a paired monitor is known to the screen`() {
        val vm = viewModel()
        assertFalse(vm.hasHeartRateSensor.value)

        paired.value = mapOf(SensorType.HEART_RATE to PairedSensor(SensorType.HEART_RATE, "AA:BB", "HR Strap 10", 1L, null, null))
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        assertTrue(vm.hasHeartRateSensor.value)
    }
}
