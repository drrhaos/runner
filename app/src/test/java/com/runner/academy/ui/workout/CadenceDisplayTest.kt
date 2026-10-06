package com.runner.academy.ui.workout

import com.runner.academy.util.WorkoutDerivation
import org.junit.Assert.assertEquals
import org.junit.Test

class CadenceDisplayTest {

    private val current = WorkoutDerivation.CURRENT_METRICS_VERSION

    @Test
    fun `a stored cadence is shown rounded, whatever the version`() {
        assertEquals(CadenceDisplay.Value(172), CadenceDisplay.of(171.6f, current, trackHasSteps = true))
        assertEquals(CadenceDisplay.Value(172), CadenceDisplay.of(171.6f, 0, trackHasSteps = false))
    }

    @Test
    fun `not computed yet with steps in the track is pending, not hidden`() {
        assertEquals(CadenceDisplay.Pending, CadenceDisplay.of(null, current - 1, trackHasSteps = true))
        assertEquals(CadenceDisplay.Pending, CadenceDisplay.of(null, 0, trackHasSteps = true))
    }

    @Test
    fun `computed without a cadence, or no steps at all, is hidden`() {
        assertEquals(CadenceDisplay.None, CadenceDisplay.of(null, current, trackHasSteps = true))
        assertEquals(CadenceDisplay.None, CadenceDisplay.of(null, 0, trackHasSteps = false))
        assertEquals(CadenceDisplay.None, CadenceDisplay.of(Float.NaN, current, trackHasSteps = true))
    }
}
