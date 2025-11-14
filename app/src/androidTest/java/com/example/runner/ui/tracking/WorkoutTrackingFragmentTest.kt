package com.example.runner.ui.tracking

import androidx.fragment.app.testing.launchFragmentInContainer
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.runner.R
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Интеграционные тесты для WorkoutTrackingFragment
 */
@RunWith(AndroidJUnit4::class)
class WorkoutTrackingFragmentTest {

    @Test
    fun `workout tracking fragment should display correctly`() {
        // Given
        val scenario = launchFragmentInContainer<WorkoutTrackingFragment>()

        // When & Then
        onView(withId(R.id.button_start))
            .check(matches(isDisplayed()))
        
        onView(withId(R.id.button_pause))
            .check(matches(not(isDisplayed())))
        
        onView(withId(R.id.button_stop))
            .check(matches(not(isDisplayed())))
        
        onView(withId(R.id.mapView))
            .check(matches(isDisplayed()))
        
        onView(withId(R.id.textView_distance))
            .check(matches(isDisplayed()))
        
        onView(withId(R.id.textView_time))
            .check(matches(isDisplayed()))
        
        onView(withId(R.id.textView_pace))
            .check(matches(isDisplayed()))
        
        onView(withId(R.id.textView_speed))
            .check(matches(isDisplayed()))
        
        onView(withId(R.id.textView_calories))
            .check(matches(isDisplayed()))
    }

    @Test
    fun `start button should be clickable`() {
        // Given
        val scenario = launchFragmentInContainer<WorkoutTrackingFragment>()

        // When & Then
        onView(withId(R.id.button_start))
            .check(matches(isDisplayed()))
            .check(matches(isClickable()))
    }

    @Test
    fun `pause button should be clickable when visible`() {
        // Given
        val scenario = launchFragmentInContainer<WorkoutTrackingFragment>()

        // When
        onView(withId(R.id.button_start)).perform(click())

        // Then
        onView(withId(R.id.button_pause))
            .check(matches(isDisplayed()))
            .check(matches(isClickable()))
    }

    @Test
    fun `stop button should be clickable when visible`() {
        // Given
        val scenario = launchFragmentInContainer<WorkoutTrackingFragment>()

        // When
        onView(withId(R.id.button_start)).perform(click())

        // Then
        onView(withId(R.id.button_stop))
            .check(matches(isDisplayed()))
            .check(matches(isClickable()))
    }

    @Test
    fun `gps status should be displayed`() {
        // Given
        val scenario = launchFragmentInContainer<WorkoutTrackingFragment>()

        // When & Then
        onView(withId(R.id.textView_gps_status))
            .check(matches(isDisplayed()))
    }

    @Test
    fun `progress bar should be displayed`() {
        // Given
        val scenario = launchFragmentInContainer<WorkoutTrackingFragment>()

        // When & Then
        onView(withId(R.id.progressBar_gps))
            .check(matches(isDisplayed()))
    }

    @Test
    fun `map view should be displayed`() {
        // Given
        val scenario = launchFragmentInContainer<WorkoutTrackingFragment>()

        // When & Then
        onView(withId(R.id.mapView))
            .check(matches(isDisplayed()))
    }

    @Test
    fun `workout statistics should be displayed`() {
        // Given
        val scenario = launchFragmentInContainer<WorkoutTrackingFragment>()

        // When & Then
        onView(withId(R.id.textView_distance))
            .check(matches(isDisplayed()))
        
        onView(withId(R.id.textView_time))
            .check(matches(isDisplayed()))
        
        onView(withId(R.id.textView_pace))
            .check(matches(isDisplayed()))
        
        onView(withId(R.id.textView_speed))
            .check(matches(isDisplayed()))
        
        onView(withId(R.id.textView_calories))
            .check(matches(isDisplayed()))
    }
}
