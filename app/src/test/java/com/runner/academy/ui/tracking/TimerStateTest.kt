package com.runner.academy.ui.tracking

import com.runner.academy.data.WorkoutSession
import org.junit.Assert.assertEquals
import org.junit.Test

class TimerStateTest {

    @Test
    fun `running without pauses`() {
        assertEquals(TimerState.RUNNING, TimerState.of(WorkoutSession(isTracking = true)))
    }

    @Test
    fun `an auto-pause shows the auto-paused state`() {
        assertEquals(TimerState.AUTO_PAUSED, TimerState.of(WorkoutSession(isTracking = true, autoPaused = true)))
    }

    @Test
    fun `a manual pause wins over an auto-pause`() {
        assertEquals(
            TimerState.PAUSED,
            TimerState.of(WorkoutSession(isTracking = true, isPaused = true, autoPaused = true))
        )
    }

    @Test
    fun `a stopped session shows no pause state`() {
        assertEquals(TimerState.RUNNING, TimerState.of(WorkoutSession(isTracking = false, autoPaused = true)))
    }
}
