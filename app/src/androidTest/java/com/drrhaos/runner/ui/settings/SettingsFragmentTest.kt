package com.drrhaos.runner.ui.settings

import androidx.fragment.app.testing.launchFragmentInContainer
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.drrhaos.runner.R
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Интеграционные тесты для SettingsFragment
 */
@RunWith(AndroidJUnit4::class)
class SettingsFragmentTest {

    @Test
    fun `settings fragment should display correctly`() {
        // Given
        val scenario = launchFragmentInContainer<SettingsFragment>()

        // When & Then
        onView(withId(R.id.textView_weight_value))
            .check(matches(isDisplayed()))
        
        onView(withId(R.id.textView_height_value))
            .check(matches(isDisplayed()))
        
        onView(withId(R.id.textView_age_value))
            .check(matches(isDisplayed()))
        
        onView(withId(R.id.textView_gender_value))
            .check(matches(isDisplayed()))
        
        onView(withId(R.id.textView_unit_system_value))
            .check(matches(isDisplayed()))
        
        onView(withId(R.id.textView_gps_accuracy_value))
            .check(matches(isDisplayed()))
        
        onView(withId(R.id.switch_auto_pause))
            .check(matches(isDisplayed()))
        
        onView(withId(R.id.switch_voice_feedback))
            .check(matches(isDisplayed()))
        
        onView(withId(R.id.button_reset_settings))
            .check(matches(isDisplayed()))
    }

    @Test
    fun `weight row should be clickable`() {
        // Given
        val scenario = launchFragmentInContainer<SettingsFragment>()

        // When & Then
        onView(withId(R.id.row_weight))
            .check(matches(isDisplayed()))
            .check(matches(isClickable()))
    }

    @Test
    fun `height row should be clickable`() {
        // Given
        val scenario = launchFragmentInContainer<SettingsFragment>()

        // When & Then
        onView(withId(R.id.row_height))
            .check(matches(isDisplayed()))
            .check(matches(isClickable()))
    }

    @Test
    fun `birth date row should be clickable`() {
        // Given
        val scenario = launchFragmentInContainer<SettingsFragment>()

        // When & Then
        onView(withId(R.id.row_age))
            .check(matches(isDisplayed()))
            .check(matches(isClickable()))
    }

    @Test
    fun `gender row should be clickable`() {
        // Given
        val scenario = launchFragmentInContainer<SettingsFragment>()

        // When & Then
        onView(withId(R.id.row_gender))
            .check(matches(isDisplayed()))
            .check(matches(isClickable()))
    }

    @Test
    fun `unit system row should be clickable`() {
        // Given
        val scenario = launchFragmentInContainer<SettingsFragment>()

        // When & Then
        onView(withId(R.id.row_unit_system))
            .check(matches(isDisplayed()))
            .check(matches(isClickable()))
    }

    @Test
    fun `gps accuracy row should be clickable`() {
        // Given
        val scenario = launchFragmentInContainer<SettingsFragment>()

        // When & Then
        onView(withId(R.id.row_gps_accuracy))
            .check(matches(isDisplayed()))
            .check(matches(isClickable()))
    }

    @Test
    fun `reset settings button should be clickable`() {
        // Given
        val scenario = launchFragmentInContainer<SettingsFragment>()

        // When & Then
        onView(withId(R.id.button_reset_settings))
            .check(matches(isDisplayed()))
            .check(matches(isClickable()))
    }

    @Test
    fun `auto pause switch should be clickable`() {
        // Given
        val scenario = launchFragmentInContainer<SettingsFragment>()

        // When & Then
        onView(withId(R.id.switch_auto_pause))
            .check(matches(isDisplayed()))
            .check(matches(isClickable()))
    }

    @Test
    fun `voice feedback switch should be clickable`() {
        // Given
        val scenario = launchFragmentInContainer<SettingsFragment>()

        // When & Then
        onView(withId(R.id.switch_voice_feedback))
            .check(matches(isDisplayed()))
            .check(matches(isClickable()))
    }
}
