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
 * Интеграционные тесты для WorkoutListFragment
 */
@RunWith(AndroidJUnit4::class)
class WorkoutListFragmentTest {

    @Test
    fun `workout list fragment should display correctly`() {
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
    fun `add workout fab should be clickable`() {
        // Given
        val scenario = launchFragmentInContainer<WorkoutListFragment>()

        // When & Then
        onView(withId(R.id.fab_add_workout))
            .check(matches(isDisplayed()))
            .check(matches(isClickable()))
    }

    @Test
    fun `recycler view should be displayed`() {
        // Given
        val scenario = launchFragmentInContainer<WorkoutListFragment>()

        // When & Then
        onView(withId(R.id.recyclerView_workouts))
            .check(matches(isDisplayed()))
    }

    @Test
    fun `statistics should be displayed`() {
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
    fun `empty state should be displayed when no workouts`() {
        // Given
        val scenario = launchFragmentInContainer<WorkoutListFragment>()

        // When & Then
        onView(withId(R.id.layout_empty_state))
            .check(matches(isDisplayed()))
    }
}
