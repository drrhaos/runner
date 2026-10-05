package com.runner.academy.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.runner.academy.util.DerivationInput
import com.runner.academy.util.Derived
import com.runner.academy.util.Effort
import com.runner.academy.util.WorkoutDerivation
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
class WorkoutStoreTest {

    private lateinit var database: WorkoutDatabase
    private lateinit var workouts: WorkoutDao
    private lateinit var efforts: BestEffortDao
    private var deferredSaves = 0

    /** Metrics that show what they were derived from. */
    private val derive: (DerivationInput) -> Derived = { input ->
        Derived(
            avgCadence = input.durationMs / 1000f,
            routePreview = input.trackJson,
            efforts = listOf(Effort(1_000, input.durationMs, 0L, input.durationMs, 0f))
        )
    }

    private lateinit var store: WorkoutStore

    @Before
    fun setUp() {
        database = WorkoutDatabase.getInMemoryDatabase(ApplicationProvider.getApplicationContext<Context>())
        workouts = database.workoutDao()
        efforts = database.bestEffortDao()
        store = WorkoutStore(database, derive, onDeferredSaved = { deferredSaves++ })
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun workout(durationMs: Long, track: String? = "track-$durationMs") = Workout(
        date = Date(durationMs),
        distance = 5f,
        duration = durationMs,
        movingDuration = durationMs,
        avgPace = 6f,
        calories = null,
        notes = null,
        type = WorkoutType.EASY_RUN,
        trackData = track
    )

    private suspend fun stored(id: Long) = workouts.getWorkoutById(id).first()!!

    @Test
    fun inlineInsert_writesTheRowWithItsMetricsAndEfforts() = runBlocking {
        val id = store.insert(workout(1_800_000L))

        val row = stored(id)
        assertEquals(1_800f, row.avgCadence!!, 0f)
        assertEquals("track-1800000", row.routePreview)
        assertEquals(WorkoutDerivation.CURRENT_METRICS_VERSION, row.metricsVersion)
        assertEquals(listOf(BestEffort(id, 1_000, 1_800_000L, 0L, 1_800_000L, 0f)), efforts.getForWorkout(id))
        assertEquals(0, deferredSaves)
    }

    @Test
    fun deferredInsert_leavesMetricsToTheBackgroundPass() = runBlocking {
        val ids = store.insertAll(
            listOf(workout(1_000L), workout(2_000L).copy(metricsVersion = 5, avgCadence = 170f)),
            WorkoutStore.Mode.DEFERRED
        )

        assertEquals(listOf(0, 0), ids.map { stored(it).metricsVersion })
        assertTrue(ids.all { efforts.getForWorkout(it).isEmpty() })
        assertEquals(1, deferredSaves)
    }

    @Test
    fun saveEdited_recomputesMetricsAndReplacesEfforts() = runBlocking {
        val id = store.insert(workout(1_800_000L))
        val edited = stored(id).copy(duration = 2_400_000L, notes = "edited", isFavorite = true)

        store.saveEdited(edited)

        val row = stored(id)
        assertEquals("edited", row.notes)
        assertTrue(row.isFavorite)
        assertEquals(2_400f, row.avgCadence!!, 0f)
        assertEquals(listOf(2_400_000L), efforts.getForWorkout(id).map { it.elapsedMs })
    }

    @Test
    fun realDerivation_marksAWorkoutWithoutTrackComputed() = runBlocking {
        val id = WorkoutStore(database).insert(workout(1_000L, track = null))

        val row = stored(id)
        assertEquals(WorkoutDerivation.CURRENT_METRICS_VERSION, row.metricsVersion)
        assertEquals(null, row.avgCadence)
    }
}
