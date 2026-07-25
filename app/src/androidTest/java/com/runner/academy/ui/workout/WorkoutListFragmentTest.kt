package com.runner.academy.ui.workout

import androidx.fragment.app.testing.launchFragmentInContainer
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.runner.academy.R
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Интеграционные тесты для WorkoutListFragment
 */
@RunWith(AndroidJUnit4::class)
class WorkoutListFragmentTest {

    @Test
    fun workout_list_fragment_should_display_correctly() {
        // Given
        val scenario = launchFragmentInContainer<WorkoutListFragment>()

        // When & Then
        onView(withId(R.id.recyclerView_workouts))
            .check(matches(isDisplayed()))
        
        onView(withId(R.id.fab_add_workout))
            .check(matches(isDisplayed()))
        
        onView(withId(R.id.textView_total_workouts))
            .check(matches(isDisplayed()))
        
        onView(withId(R.id.textView_total_distance))
            .check(matches(isDisplayed()))
        
        onView(withId(R.id.textView_avg_pace))
            .check(matches(isDisplayed()))
    }

    @Test
    fun add_workout_fab_should_be_clickable() {
        // Given
        val scenario = launchFragmentInContainer<WorkoutListFragment>()

        // When & Then
        onView(withId(R.id.fab_add_workout))
            .check(matches(isDisplayed()))
            .check(matches(isClickable()))
    }

    @Test
    fun recycler_view_should_be_displayed() {
        // Given
        val scenario = launchFragmentInContainer<WorkoutListFragment>()

        // When & Then
        onView(withId(R.id.recyclerView_workouts))
            .check(matches(isDisplayed()))
    }

    @Test
    fun statistics_should_be_displayed() {
        // Given
        val scenario = launchFragmentInContainer<WorkoutListFragment>()

        // When & Then
        onView(withId(R.id.textView_total_workouts))
            .check(matches(isDisplayed()))
        
        onView(withId(R.id.textView_total_distance))
            .check(matches(isDisplayed()))
        
        onView(withId(R.id.textView_avg_pace))
            .check(matches(isDisplayed()))
    }

    @Test
    fun empty_state_should_be_displayed_when_no_workouts() {
        // Given
        val scenario = launchFragmentInContainer<WorkoutListFragment>()

        // When & Then
        onView(withId(R.id.layout_empty_state))
            .check(matches(isDisplayed()))
    }
}
