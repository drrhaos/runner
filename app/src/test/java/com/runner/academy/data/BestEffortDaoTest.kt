package com.runner.academy.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Date

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BestEffortDaoTest {

    private lateinit var database: WorkoutDatabase
    private lateinit var workouts: WorkoutDao
    private lateinit var efforts: BestEffortDao

    @Before
    fun setUp() {
        database = WorkoutDatabase.getInMemoryDatabase(ApplicationProvider.getApplicationContext<Context>())
        workouts = database.workoutDao()
        efforts = database.bestEffortDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private suspend fun insertWorkout(dateMs: Long): Long = workouts.insertWorkout(
        Workout(
            date = Date(dateMs),
            distance = 12f,
            duration = 3_600_000L,
            movingDuration = 3_600_000L,
            avgPace = 5f,
            calories = null,
            notes = null,
            type = WorkoutType.LONG_RUN
        )
    )

    private fun effort(workoutId: Long, distance: RecordDistance, elapsedMs: Long) =
        BestEffort(workoutId, distance.meters, elapsedMs, 0L, elapsedMs, 0f)

    @Test
    fun replaceForWorkout_replacesOnlyThatWorkout() = runBlocking {
        val first = insertWorkout(1_000L)
        val second = insertWorkout(2_000L)
        efforts.replaceForWorkout(first, listOf(effort(first, RecordDistance.KM_1, 300_000L)))
        efforts.replaceForWorkout(second, listOf(effort(second, RecordDistance.KM_1, 290_000L)))

        efforts.replaceForWorkout(
            first,
            listOf(effort(first, RecordDistance.KM_1, 280_000L), effort(first, RecordDistance.KM_5, 1_500_000L))
        )

        assertEquals(listOf(280_000L, 1_500_000L), efforts.getForWorkout(first).map { it.elapsedMs })
        assertEquals(listOf(290_000L), efforts.getForWorkout(second).map { it.elapsedMs })

        efforts.replaceForWorkout(first, emptyList())
        assertTrue(efforts.getForWorkout(first).isEmpty())
    }

    @Test
    fun deletingWorkout_cascadesToItsEfforts() = runBlocking {
        val id = insertWorkout(1_000L)
        efforts.replaceForWorkout(id, listOf(effort(id, RecordDistance.KM_5, 1_500_000L)))

        workouts.deleteWorkout(workouts.getWorkoutById(id).first()!!)

        assertTrue(efforts.getForWorkout(id).isEmpty())
    }

    @Test
    fun eligibleEfforts_skipWorkoutsExcludedFromRecords() = runBlocking {
        val kept = insertWorkout(1_000L)
        val excluded = insertWorkout(2_000L)
        efforts.replaceForWorkout(kept, listOf(effort(kept, RecordDistance.KM_5, 1_500_000L)))
        efforts.replaceForWorkout(excluded, listOf(effort(excluded, RecordDistance.KM_5, 1_400_000L)))

        workouts.setExcludeFromRecords(excluded, true)

        val rows = efforts.observeEligibleEfforts().first()
        assertEquals(
            listOf(EffortRow(kept, 5_000, 1_500_000L, 0L, 1_500_000L, 0f, Date(1_000L), WorkoutType.LONG_RUN)),
            rows
        )
    }

    @Test
    fun recordDistances_areTheFiveAgreedOnes() {
        assertEquals(listOf(1_000, 5_000, 10_000, 21_097, 42_195), RecordDistance.entries.map { it.meters })
        assertEquals(RecordDistance.HALF_MARATHON, RecordDistance.ofMeters(21_097))
    }
}
