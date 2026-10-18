package com.runner.academy.ui.settings

import com.runner.academy.R
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsHintTextsTest {

    @Test
    fun `the auto-pause hint warns about GPS alone only when steps are out`() {
        assertEquals(
            listOf(R.string.settings_hint_auto_pause),
            SettingsHintTexts.autoPause(hasStepSensor = true, stepsForDistance = true)
        )
        assertEquals(
            listOf(R.string.settings_hint_auto_pause, R.string.settings_hint_auto_pause_gps_only),
            SettingsHintTexts.autoPause(hasStepSensor = true, stepsForDistance = false)
        )
        assertEquals(
            listOf(R.string.settings_hint_auto_pause, R.string.settings_hint_auto_pause_no_sensor),
            SettingsHintTexts.autoPause(hasStepSensor = false, stepsForDistance = true)
        )
    }

    @Test
    fun `without a step sensor the warning replaces the steps hint`() {
        assertEquals(listOf(R.string.settings_hint_steps_for_distance), SettingsHintTexts.steps(hasStepSensor = true))
        assertEquals(listOf(R.string.settings_hint_steps_no_sensor), SettingsHintTexts.steps(hasStepSensor = false))
    }
}
