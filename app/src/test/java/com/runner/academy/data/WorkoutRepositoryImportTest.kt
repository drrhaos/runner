package com.runner.academy.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.runner.academy.util.WorkoutDerivation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
        date = Date(n * DAY),
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

    /** A run of [km] at [startMin] (minutes after the first day) for [durationMin]. */
    private fun at(startMin: Long, durationMin: Long, km: Float = 5f) =
        imported(0).copy(date = Date(DAY + startMin * MINUTE), duration = durationMin * MINUTE, movingDuration = durationMin * MINUTE, distance = km)

    private suspend fun batch(vararg workouts: Workout, fromBackup: Boolean = false) =
        repository.importBatch(workouts.mapIndexed { i, w -> ImportCandidate(w, "file$i.gpx") }, fromBackup)

    private suspend fun savedStarts() = repository.getAllWorkouts().first().map { (it.date.time - DAY) / MINUTE }.sorted()

    private suspend fun pending() = repository.observePendingConflicts().first()

    @Test
    fun batch_savesNewOnes_dropsCopies_andPutsOverlapsAside() = runBlocking {
        repository.importGpx(listOf(at(0, 30)))

        val result = batch(at(0, 30), at(10, 30, km = 4f), at(100, 30))

        assertEquals(1, result.insertedIds.size)
        assertEquals(1, result.pending)
        assertEquals(1, result.duplicates)
        assertEquals(listOf(0L, 100L), savedStarts())
        val waiting = pending().single()
        assertEquals("file1.gpx", waiting.incoming.source)
        assertNotNull(waiting.others.single().existingId)
    }

    @Test
    fun batch_checksAgainstItsOwnEarlierRows() = runBlocking {
        // A GPX and a TCX of one run in the same folder
        val result = batch(at(0, 30, km = 5.1f), at(0, 30, km = 5f))

        assertEquals(1, result.insertedIds.size)
        assertEquals(1, result.pending)
    }

    @Test
    fun aWaitingOne_isNotInTheListOrTotals() = runBlocking {
        repository.importGpx(listOf(at(0, 30)))
        batch(at(10, 30, km = 4f))

        assertEquals(1, repository.getAllWorkouts().first().size)
        assertEquals(5f, repository.getTotalDistance()!!, 0.001f)
        assertEquals(1, repository.observePendingImportCount().first())
    }

    @Test
    fun runAgain_afterAnInterruption_onlyTheRestIsImported() = runBlocking {
        // The first batch of an interrupted import was saved, the second was not
        batch(at(0, 30), at(100, 30))
        batch(at(10, 30, km = 4f))

        val again = listOf(batch(at(0, 30), at(100, 30)), batch(at(10, 30, km = 4f), at(200, 30)))

        assertEquals(listOf(0, 1), again.map { it.insertedIds.size })
        assertEquals(listOf(2, 1), again.map { it.duplicates })
        assertEquals("the waiting one is not put aside twice", 1, pending().size)
    }

    @Test
    fun keepExisting_dropsTheWaitingOne() = runBlocking {
        repository.importGpx(listOf(at(0, 30)))
        batch(at(10, 30, km = 4f))

        repository.resolvePendingImport(pending().single().pendingId, ConflictResolution.KEEP_EXISTING)

        assertEquals(emptyList<PendingConflict>(), pending())
        assertEquals(listOf(0L), savedStarts())
    }

    @Test
    fun keepBoth_savesItWithItsMetrics() = runBlocking {
        repository.importGpx(listOf(at(0, 30)))
        batch(at(10, 30, km = 4f), fromBackup = true)

        repository.resolvePendingImport(pending().single().pendingId, ConflictResolution.KEEP_BOTH)

        assertEquals(emptyList<PendingConflict>(), pending())
        assertEquals(listOf(0L, 10L), savedStarts())
        assertEquals(listOf(WorkoutDerivation.CURRENT_METRICS_VERSION, WorkoutDerivation.CURRENT_METRICS_VERSION), storedVersions())
    }

    @Test
    fun replace_deletesEverySavedOneItOverlaps() = runBlocking {
        repository.importGpx(listOf(at(0, 20), at(50, 30), at(200, 30)))
        batch(at(10, 60, km = 9f))

        repository.resolvePendingImport(pending().single().pendingId, ConflictResolution.REPLACE)

        assertEquals(listOf(10L, 200L), savedStarts())
        assertEquals(9f + 5f, repository.getTotalDistance()!!, 0.001f)
    }

    @Test
    fun aWaitingOne_seesTheOtherWaitingOnes_andWhatWasDeletedMeanwhile() = runBlocking {
        val saved = repository.importGpx(listOf(at(0, 30))).ids.single()
        batch(at(5, 30, km = 4f), at(10, 30, km = 3f))
        assertEquals(listOf(listOf(saved, null), listOf(saved, null)), pending().map { c -> c.others.map { it.existingId } })

        repository.deleteWorkout(repository.getWorkoutById(saved).first()!!)

        assertEquals(listOf(1, 1), pending().map { it.others.size })
    }

    @Test
    fun resolveAll_decidesEachInStartOrder() = runBlocking {
        repository.importGpx(listOf(at(0, 30), at(100, 30)))
        batch(at(110, 30, km = 4f), at(10, 30, km = 4f))

        repository.resolveAllPendingImports(ConflictResolution.REPLACE)

        assertEquals(listOf(10L, 110L), savedStarts())
        assertEquals(0, repository.observePendingImportCount().first())
    }

    @Test
    fun aResolvedOne_isGone_soASecondDecisionDoesNothing() = runBlocking {
        repository.importGpx(listOf(at(0, 30)))
        batch(at(10, 30, km = 4f))
        val id = pending().single().pendingId

        repository.resolvePendingImport(id, ConflictResolution.KEEP_BOTH)
        repository.resolvePendingImport(id, ConflictResolution.KEEP_BOTH)

        assertEquals(2, repository.getAllWorkouts().first().size)
    }

    private companion object {
        const val MINUTE = 60_000L
        const val DAY = 24 * 60 * MINUTE
    }
}
