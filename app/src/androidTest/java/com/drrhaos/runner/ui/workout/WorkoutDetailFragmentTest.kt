package com.drrhaos.runner.ui.workout

import androidx.fragment.app.testing.launchFragmentInContainer
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.drrhaos.runner.R
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Интеграционные тесты для WorkoutDetailFragment
 */
@RunWith(AndroidJUnit4::class)
class WorkoutDetailFragmentTest {

    @Test
    fun `workout detail fragment should display correctly`() {
        // Given
        val scenario = launchFragmentInContainer<WorkoutDetailFragment>()

        // When & Then
        onView(withId(R.id.mapView_detail))
            .check(matches(isDisplayed()))
        
        onView(withId(R.id.textView_detail_date))
            .check(matches(isDisplayed()))
        
        onView(withId(R.id.textView_detail_time))
            .check(matches(isDisplayed()))
        
        onView(withId(R.id.textView_detail_type))
            .check(matches(isDisplayed()))
        
        onView(withId(R.id.textView_detail_distance))
            .check(matches(isDisplayed()))
        
        onView(withId(R.id.textView_detail_duration))
            .check(matches(isDisplayed()))
        
        onView(withId(R.id.textView_detail_pace))
            .check(matches(isDisplayed()))
        
        onView(withId(R.id.textView_detail_avg_speed))
            .check(matches(isDisplayed()))
        
        onView(withId(R.id.textView_detail_calories))
            .check(matches(isDisplayed()))
    }

    @Test
    fun `share button should be clickable`() {
        // Given
        val scenario = launchFragmentInContainer<WorkoutDetailFragment>()

        // When & Then
        onView(withId(R.id.button_share))
            .check(matches(isDisplayed()))
            .check(matches(isClickable()))
    }

    @Test
    fun `delete button should be clickable`() {
        // Given
        val scenario = launchFragmentInContainer<WorkoutDetailFragment>()

        // When & Then
        onView(withId(R.id.button_delete))
            .check(matches(isDisplayed()))
            .check(matches(isClickable()))
    }

    @Test
    fun `map view should be displayed`() {
        // Given
        val scenario = launchFragmentInContainer<WorkoutDetailFragment>()

        // When & Then
        onView(withId(R.id.mapView_detail))
            .check(matches(isDisplayed()))
    }

    @Test
    fun `progress bar should be displayed`() {
        // Given
        val scenario = launchFragmentInContainer<WorkoutDetailFragment>()

        // When & Then
        onView(withId(R.id.progressBar_map_loading))
            .check(matches(isDisplayed()))
    }

    @Test
    fun `workout details should be displayed`() {
        // Given
        val scenario = launchFragmentInContainer<WorkoutDetailFragment>()

        // When & Then
        onView(withId(R.id.textView_detail_date))
            .check(matches(isDisplayed()))
        
        onView(withId(R.id.textView_detail_time))
            .check(matches(isDisplayed()))
        
        onView(withId(R.id.textView_detail_type))
            .check(matches(isDisplayed()))
        
        onView(withId(R.id.textView_detail_distance))
            .check(matches(isDisplayed()))
        
        onView(withId(R.id.textView_detail_duration))
            .check(matches(isDisplayed()))
        
        onView(withId(R.id.textView_detail_pace))
            .check(matches(isDisplayed()))
        
        onView(withId(R.id.textView_detail_avg_speed))
            .check(matches(isDisplayed()))
        
        onView(withId(R.id.textView_detail_calories))
            .check(matches(isDisplayed()))
    }
}
