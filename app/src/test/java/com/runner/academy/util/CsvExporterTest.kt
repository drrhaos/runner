package com.runner.academy.util

import androidx.test.core.app.ApplicationProvider
import com.runner.academy.data.Workout
import com.runner.academy.data.WorkoutType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Date

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CsvExporterTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun workout(
        avgCadence: Float? = null,
        elevationGain: Float? = null,
        elevationLoss: Float? = null,
        notes: String? = null
    ) = Workout(
        date = Date(1_800_000_000_000L),
        distance = 5f,
        duration = 1_800_000L,
        movingDuration = 1_710_000L,
        avgPace = 5.7f,
        calories = 300,
        notes = notes,
        type = WorkoutType.EASY_RUN,
        elevationGain = elevationGain,
        elevationLoss = elevationLoss,
        avgCadence = avgCadence
    )

    private fun rows(vararg workouts: Workout): List<List<String>> =
        CsvExporter.exportWorkoutsToCsv(workouts.toList(), context).trimEnd('\n').split('\n').map { it.split(',') }

    @Test
    fun `the header names the release 3 columns and matches the rows`() {
        val (header, row) = rows(workout(avgCadence = 171.6f, elevationGain = 42.4f, elevationLoss = 40.6f, notes = "n"))

        assertEquals(
            listOf(
                "Date", "Time", "Workout type", "Distance (km)", "Duration (min)", "Pace (min/km)", "Calories",
                "Moving time (min)", "Elevation gain (m)", "Elevation loss (m)", "Cadence (spm)", "Notes"
            ),
            header
        )
        assertEquals(header.size, row.size)
        assertEquals("28.50", row[7])
        assertEquals("42", row[8])
        assertEquals("41", row[9])
        assertEquals("172", row[10])
        assertEquals("n", row[11])
    }

    private fun statistics(averageCadence: Float?) = CsvExporter.exportStatisticsToCsv(
        totalWorkouts = 2, totalDistance = 10f, totalDuration = 3_600_000L, totalCalories = 600,
        averagePace = 6f, averageDistance = 5f, averageDuration = 1_800_000L, bestPace = 5.5f,
        longestDistance = 6f, longestDuration = 2_000_000L, workoutsByType = emptyMap(),
        distanceByType = emptyMap(), averageCadence = averageCadence, context = context
    )

    @Test
    fun `the statistics carry the average cadence only when some workout has it`() {
        assertTrue(statistics(168.4f).lines().contains("Average cadence (spm),168"))
        assertTrue(statistics(null).lines().none { it.startsWith("Average cadence") })
    }

    @Test
    fun `missing metrics are empty cells, not zero`() {
        val (header, row) = rows(workout())

        assertEquals(header.size, row.size)
        assertEquals(listOf("", "", ""), row.subList(8, 11))
        assertTrue(row[0].matches(Regex("""\d{4}-\d{2}-\d{2}""")))
        assertTrue(row[1].matches(Regex("""\d{2}:\d{2}""")))
    }
}
