package com.runner.academy.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Date

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WorkoutDaoTest {

    private lateinit var database: WorkoutDatabase
    private lateinit var dao: WorkoutDao

    @Before
    fun setUp() {
        database = WorkoutDatabase.getInMemoryDatabase(ApplicationProvider.getApplicationContext<Context>())
        dao = database.workoutDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun workout(duration: Long, moving: Long) = Workout(
        date = Date(1_000L),
        distance = 5f,
        duration = duration,
        movingDuration = moving,
        avgPace = 6f,
        calories = null,
        notes = null,
        type = WorkoutType.EASY_RUN
    )

    @Test
    fun averageMovingDuration_isOverMovingTimeNotTotal() = runBlocking {
        dao.insertWorkout(workout(duration = 3_600_000L, moving = 3_000_000L))
        dao.insertWorkout(workout(duration = 1_800_000L, moving = 1_800_000L))

        assertEquals(2_400_000L, dao.getAverageMovingDuration())
    }

    @Test
    fun averageMovingDuration_noWorkouts_isNull() = runBlocking {
        assertEquals(null, dao.getAverageMovingDuration())
    }

    @Test
    fun saveEdited_rewritesTheStoredRow() = runBlocking {
        val id = dao.insertWorkout(workout(duration = 3_600_000L, moving = 3_000_000L))
        val stored = dao.getWorkoutById(id).first()!!

        dao.saveEdited(stored.copy(notes = "edited", excludeFromRecords = true))

        assertEquals(stored.copy(notes = "edited", excludeFromRecords = true), dao.getWorkoutById(id).first())
    }

    @Test
    fun saveEdited_rejectsANewWorkout() {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { dao.saveEdited(workout(duration = 60_000L, moving = 60_000L)) }
        }
    }
}
