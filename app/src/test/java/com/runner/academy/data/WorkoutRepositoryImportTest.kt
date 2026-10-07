package com.runner.academy.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.runner.academy.util.WorkoutDerivation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Date

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WorkoutRepositoryImportTest {

    private lateinit var database: WorkoutDatabase
    private lateinit var repository: WorkoutRepository
    private var deferredSaves = 0

    @Before
    fun setUp() {
        database = WorkoutDatabase.getInMemoryDatabase(ApplicationProvider.getApplicationContext<Context>())
        repository = WorkoutRepository(database, onDeferredSaved = { deferredSaves++ })
    }

    @After
    fun tearDown() = database.close()

    /** A row as a file holds it: with an id of the other device and metrics of its own. */
    private fun imported(n: Long) = Workout(
        id = 100 + n,
        date = Date(n),
        distance = 5f,
        duration = 1_800_000L,
        movingDuration = 1_800_000L,
        avgPace = 6f,
        calories = null,
        notes = null,
        type = WorkoutType.EASY_RUN,
        trackData = null,
        metricsVersion = 99
    )

    private suspend fun storedVersions() = repository.getAllWorkouts().first().map { it.metricsVersion }

    @Test
    fun backupImport_savesNewRowsForTheBackgroundPass() = runBlocking {
        val ids = repository.importBackup(listOf(imported(1), imported(2)))

        assertEquals(2, ids.size)
        assertEquals(listOf(0, 0), storedVersions())
        assertEquals(1, deferredSaves)
    }

    @Test
    fun gpxImport_computesMetricsBeforeSaving() = runBlocking {
        val ids = repository.importGpx(listOf(imported(1))).ids

        assertEquals(1, ids.size)
        assertEquals(listOf(WorkoutDerivation.CURRENT_METRICS_VERSION), storedVersions())
        assertEquals(0, deferredSaves)
    }
}
