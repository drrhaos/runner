package com.drrhaos.runner.ui.tracking

import androidx.fragment.app.testing.launchFragmentInContainer
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.drrhaos.runner.R
import org.hamcrest.Matchers.not
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Интеграционные тесты для WorkoutTrackingFragment
 */
@RunWith(AndroidJUnit4::class)
class WorkoutTrackingFragmentTest {

    @Test
    fun workout_tracking_fragment_should_display_correctly() {
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
        
        onView(withId(R.id.textView_workout_distance))
            .check(matches(isDisplayed()))
        
        onView(withId(R.id.textView_workout_time))
            .check(matches(isDisplayed()))
        
        onView(withId(R.id.textView_workout_pace))
            .check(matches(isDisplayed()))
        
        onView(withId(R.id.textView_avg_speed))
            .check(matches(isDisplayed()))
        
        onView(withId(R.id.textView_calories))
            .check(matches(isDisplayed()))
    }

    @Test
    fun start_button_should_be_clickable() {
        // Given
        val scenario = launchFragmentInContainer<WorkoutTrackingFragment>()

        // When & Then
        onView(withId(R.id.button_start))
            .check(matches(isDisplayed()))
            .check(matches(isClickable()))
    }

    @Test
    fun pause_button_should_be_clickable_when_visible() {
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
    fun stop_button_should_be_clickable_when_visible() {
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
    fun gps_status_should_be_displayed() {
        // Given
        val scenario = launchFragmentInContainer<WorkoutTrackingFragment>()

        // When & Then
        onView(withId(R.id.layout_gps_status))
            .check(matches(isDisplayed()))
    }


    @Test
    fun map_view_should_be_displayed() {
        // Given
        val scenario = launchFragmentInContainer<WorkoutTrackingFragment>()

        // When & Then
        onView(withId(R.id.mapView))
            .check(matches(isDisplayed()))
    }

    @Test
    fun workout_statistics_should_be_displayed() {
        // Given
        val scenario = launchFragmentInContainer<WorkoutTrackingFragment>()

        // When & Then
        onView(withId(R.id.textView_workout_distance))
            .check(matches(isDisplayed()))
        
        onView(withId(R.id.textView_workout_time))
            .check(matches(isDisplayed()))
        
        onView(withId(R.id.textView_workout_pace))
            .check(matches(isDisplayed()))
        
        onView(withId(R.id.textView_avg_speed))
            .check(matches(isDisplayed()))
        
        onView(withId(R.id.textView_calories_burned))
            .check(matches(isDisplayed()))
    }
}
