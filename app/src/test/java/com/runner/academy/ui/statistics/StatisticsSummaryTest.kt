package com.runner.academy.ui.statistics

import com.runner.academy.data.WorkoutStatsRow
import com.runner.academy.data.WorkoutType
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Date

class StatisticsSummaryTest {

    private val now = Date(1_800_000_000_000L)

    private fun row(distance: Float, duration: Long, moving: Long, avgPace: Float) = WorkoutStatsRow(
        date = Date(now.time - 86_400_000L),
        distance = distance,
        duration = duration,
        movingDuration = moving,
        avgPace = avgPace,
        calories = 100,
        type = WorkoutType.EASY_RUN
    )

    @Test
    fun paceFromMovingTime_longestByTotalTime() {
        val rows = listOf(
            // 10 km in 60 min with 10 min of auto-pause
            row(distance = 10f, duration = 3_600_000L, moving = 3_000_000L, avgPace = 5f),
            // 5 km, 40 min with no pause: longer moving time, shorter total
            row(distance = 5f, duration = 3_300_000L, moving = 3_300_000L, avgPace = 11f)
        )

        val data = StatisticsSummary.of(rows, now)

        assertEquals(6_900_000L, data.totalDuration)
        assertEquals(6_300_000L, data.totalMovingDuration)
        // Σdistance / Σmoving = 105 min / 15 km
        assertEquals(7f, data.averagePace, 0.0001f)
        assertEquals(3_150_000L, data.averageDuration)
        assertEquals(5f, data.bestPace, 0f)
        assertEquals(3_600_000L, data.longestDuration)
        assertEquals(10f, data.longestDistance, 0f)
        assertEquals(2, data.workoutsThisWeek)
    }

    @Test
    fun noRows_isEmptyStatistics() {
        assertEquals(StatisticsData(), StatisticsSummary.of(emptyList(), now))
    }
}
