package com.runner.academy.ui.workout

import org.junit.Assert.assertEquals
import org.junit.Test

class WorkoutTitleTest {

    @Test
    fun `the list shows the title, else the type`() {
        assertEquals("Morning Run", WorkoutTitle.label("Morning Run", "Easy run"))
        assertEquals("Easy run", WorkoutTitle.label(null, "Easy run"))
        assertEquals("Easy run", WorkoutTitle.label("  ", "Easy run"))
    }

    @Test
    fun `a blank name is none`() {
        assertEquals("Lunch Run", WorkoutTitle.clean("  Lunch Run "))
        assertEquals(null, WorkoutTitle.clean(" "))
        assertEquals(null, WorkoutTitle.clean(null))
    }
}
