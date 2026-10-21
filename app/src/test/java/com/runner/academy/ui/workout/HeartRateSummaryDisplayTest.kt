package com.runner.academy.ui.workout

import com.runner.academy.util.WorkoutDerivation
import org.junit.Assert.assertEquals
import org.junit.Test

class HeartRateSummaryDisplayTest {

    private val current = WorkoutDerivation.CURRENT_METRICS_VERSION

    @Test
    fun `a stored average is shown with its maximum, whatever the version`() {
        assertEquals(
            HeartRateSummaryDisplay.Value(152, 178),
            HeartRateSummaryDisplay.of(152, 178, current, trackHasHeartRate = true)
        )
        assertEquals(
            HeartRateSummaryDisplay.Value(152, null),
            HeartRateSummaryDisplay.of(152, null, 0, trackHasHeartRate = false)
        )
    }

    @Test
    fun `a row of the previous version with heart rate in the track is pending`() {
        assertEquals(HeartRateSummaryDisplay.Pending, HeartRateSummaryDisplay.of(null, null, current - 1, trackHasHeartRate = true))
        assertEquals(HeartRateSummaryDisplay.Pending, HeartRateSummaryDisplay.of(null, null, 0, trackHasHeartRate = true))
    }

    @Test
    fun `a row of the previous version without heart rate in the track is not pending`() {
        assertEquals(HeartRateSummaryDisplay.None, HeartRateSummaryDisplay.of(null, null, current - 1, trackHasHeartRate = false))
    }

    @Test
    fun `computed without an average is hidden`() {
        assertEquals(HeartRateSummaryDisplay.None, HeartRateSummaryDisplay.of(null, 170, current, trackHasHeartRate = true))
    }
}
