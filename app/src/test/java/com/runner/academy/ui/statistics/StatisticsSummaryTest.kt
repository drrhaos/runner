package com.runner.academy.ui.statistics

import com.runner.academy.data.WorkoutStatsRow
import com.runner.academy.data.WorkoutType
import com.runner.academy.util.PaceMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun averagePace_isPaceMathOverTotals_zeroNotNaN() {
        val rows = listOf(
            row(distance = 10f, duration = 3_600_000L, moving = 3_000_000L, avgPace = 5f),
            row(distance = 5f, duration = 3_300_000L, moving = 3_300_000L, avgPace = 11f)
        )
        assertEquals(PaceMath.avgPace(15f, 6_300_000L), StatisticsSummary.of(rows, now).averagePace, 0f)

        val broken = listOf(row(distance = Float.NaN, duration = 60_000L, moving = 60_000L, avgPace = 0f))
        assertEquals(0f, StatisticsSummary.of(broken, now).averagePace, 0f)
    }

    @Test
    fun averageCadence_isWeightedByMovingTime_overWorkoutsWithCadence() {
        val rows = listOf(
            // 30 min at 180, 10 min at 160: (180·30 + 160·10) / 40 = 175
            row(distance = 6f, duration = 1_900_000L, moving = 1_800_000L, avgPace = 5f).copy(avgCadence = 180f),
            row(distance = 2f, duration = 600_000L, moving = 600_000L, avgPace = 5f).copy(avgCadence = 160f),
            // No steps: left out, not counted as zero
            row(distance = 10f, duration = 3_600_000L, moving = 3_600_000L, avgPace = 6f)
        )

        assertEquals(175f, StatisticsSummary.of(rows, now).averageCadence!!, 0.001f)
    }

    @Test
    fun averageCadence_isNullWithoutWorkoutsWithCadence() {
        val rows = listOf(row(distance = 10f, duration = 3_600_000L, moving = 3_600_000L, avgPace = 6f))

        assertNull(StatisticsSummary.of(rows, now).averageCadence)
        assertNull(StatisticsSummary.of(emptyList(), now).averageCadence)
    }

    @Test
    fun noRows_isEmptyStatistics() {
        assertEquals(StatisticsData(), StatisticsSummary.of(emptyList(), now))
    }
}
