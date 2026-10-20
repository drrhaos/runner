package com.runner.academy.ui.workout

import com.runner.academy.data.WorkoutMonth
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MonthLabelTest {

    @Test
    fun `russian is the nominative month, capitalised, with the year`() {
        assertEquals("Октябрь 2026", MonthLabel.of(WorkoutMonth(2026, 10, 1), Locale.forLanguageTag("ru")))
        assertEquals("Май 2025", MonthLabel.of(WorkoutMonth(2025, 5, 1), Locale.forLanguageTag("ru")))
    }

    @Test
    fun `english is the month and the year`() {
        assertEquals("October 2026", MonthLabel.of(WorkoutMonth(2026, 10, 1), Locale.US))
    }
}
