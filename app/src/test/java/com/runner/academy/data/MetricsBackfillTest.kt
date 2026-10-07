package com.runner.academy.data

import android.content.Context
import android.database.sqlite.SQLiteBlobTooBigException
import android.database.sqlite.SQLiteException
import androidx.test.core.app.ApplicationProvider
import com.runner.academy.util.DerivationInput
import com.runner.academy.util.Derived
import com.runner.academy.util.Effort
import com.runner.academy.util.WorkoutDerivation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Date
import kotlin.coroutines.cancellation.CancellationException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MetricsBackfillTest {

    private lateinit var database: WorkoutDatabase
    private lateinit var workouts: WorkoutDao
    private lateinit var efforts: BestEffortDao
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Metrics that show what they were derived from. */
    private val derive: (DerivationInput) -> Derived = { input ->
        Derived(
            avgCadence = input.durationMs / 1000f,
            efforts = listOf(Effort(1_000, input.durationMs, 0L, input.durationMs, 0f))
        )
    }

    @Before
    fun setUp() {
        database = WorkoutDatabase.getInMemoryDatabase(ApplicationProvider.getApplicationContext<Context>())
        workouts = database.workoutDao()
        efforts = database.bestEffortDao()
    }

    @After
    fun tearDown() {
        scope.cancel()
        database.close()
    }

    private fun backfill(
        derive: (DerivationInput) -> Derived = this.derive,
        version: Int = WorkoutDerivation.CURRENT_METRICS_VERSION,
        isWorkoutActive: suspend () -> Boolean = { false }
    ) = MetricsBackfill(database, scope, isWorkoutActive, derive, version)

    /** Row [n] is dated n seconds and lasts n minutes; uncomputed unless [version] is given. */
    private suspend fun insert(n: Int, track: String? = "track-$n", version: Int = 0): Long =
        workouts.insertWorkout(
            Workout(
                date = Date(n * 1_000L),
                distance = 5f,
                duration = n * 60_000L,
                movingDuration = n * 60_000L,
                avgPace = 6f,
                calories = null,
                notes = null,
                type = WorkoutType.EASY_RUN,
                trackData = track,
                metricsVersion = version
            )
        )

    private suspend fun row(id: Long) = workouts.getWorkoutById(id).first()!!

    /** Every row computed by [derive] at [version], as one uninterrupted pass leaves it. */
    private suspend fun assertComputedByOnePass(ids: List<Long>, version: Int = WorkoutDerivation.CURRENT_METRICS_VERSION) {
        for (id in ids) {
            val row = row(id)
            assertEquals(version, row.metricsVersion)
            assertEquals(row.duration / 1000f, row.avgCadence!!, 0f)
            assertEquals(listOf(row.duration), efforts.getForWorkout(id).map { it.elapsedMs })
        }
    }

    @Test
    fun interruptedPass_resumesWhereItStopped() = runBlocking {
        val ids = (1..10).map { insert(it) }
        val derivedDurations = mutableListOf<Long>()
        val dying = backfill(derive = { input ->
            if (derivedDurations.size == 3) throw CancellationException("process killed")
            derivedDurations += input.durationMs
            derive(input)
        })

        try {
            dying.runOnce()
            fail("the pass should stop")
        } catch (expected: CancellationException) {
        }

        // Newest first: rows 10, 9, 8 are done, the rest wait
        assertEquals(listOf(600_000L, 540_000L, 480_000L), derivedDurations)
        assertEquals(7, workouts.countWithMetricsBelow(WorkoutDerivation.CURRENT_METRICS_VERSION))
        assertEquals("a stopped pass is not shown as running", BackfillState.Idle, dying.progress.value)

        val report = backfill().runOnce()

        assertEquals(7, report.computed)
        assertComputedByOnePass(ids)
    }

    @Test
    fun deriveFailingOnOneRow_marksItAndComputesTheOlderRows() = runBlocking {
        val older = insert(1)
        val poison = insert(2, track = "poison")
        val newer = insert(3)

        val report = backfill(derive = { input ->
            if (input.trackJson == "poison") throw RuntimeException("bug in derivation") else derive(input)
        }).runOnce()

        assertEquals(2, report.computed)
        assertEquals(1, report.unreadable)
        val row = row(poison)
        assertEquals(WorkoutDerivation.CURRENT_METRICS_VERSION, row.metricsVersion)
        assertNull(row.avgCadence)
        assertTrue(efforts.getForWorkout(poison).isEmpty())
        assertComputedByOnePass(listOf(newer, older))
        assertEquals("never retried", 0, backfill().runOnce().unreadable)
    }

    @Test
    fun rowTooBigForCursor_isRecognizedOnlyByItsReadError() {
        val tooBig = IllegalStateException(
            "Couldn't read row 0, col 1 from CursorWindow.  Make sure the Cursor is initialized correctly before accessing data from it."
        )
        assertTrue(MetricsBackfill.isRowTooBig(tooBig, sdkInt = 27))
        assertTrue(MetricsBackfill.isRowTooBig(SQLiteBlobTooBigException("row too big"), sdkInt = 28))

        assertFalse("from API 28 the blob exception says it", MetricsBackfill.isRowTooBig(tooBig, sdkInt = 28))
        assertFalse(MetricsBackfill.isRowTooBig(IllegalStateException("attempt to re-open an already-closed object"), sdkInt = 27))
        assertFalse(MetricsBackfill.isRowTooBig(CancellationException("Couldn't read row"), sdkInt = 27))
        assertFalse(MetricsBackfill.isRowTooBig(SQLiteException("database is locked"), sdkInt = 28))
    }

    @Test
    fun nothingToCompute_staysIdle() = runBlocking {
        insert(1, version = WorkoutDerivation.CURRENT_METRICS_VERSION)
        val pass = backfill()

        pass.runOnce()

        assertEquals(BackfillState.Idle, pass.progress.value)
    }

    @Test
    fun brokenTrack_isMarkedComputedAndTheOthersAreComputed() = runBlocking {
        val broken = insert(1, track = "{not a track")
        val none = insert(2, track = null)
        val good = insert(3)

        val report = backfill(derive = { input ->
            if (input.trackJson == "track-3") derive(input) else WorkoutDerivation.derive(input)
        }).runOnce()

        assertEquals(3, report.computed)
        for (id in listOf(broken, none)) {
            val row = row(id)
            assertEquals(WorkoutDerivation.CURRENT_METRICS_VERSION, row.metricsVersion)
            assertNull(row.avgCadence)
            assertTrue(efforts.getForWorkout(id).isEmpty())
        }
        assertComputedByOnePass(listOf(good))
        assertEquals("never retried", 0, backfill().runOnce().computed)
    }

    @Test
    fun outOfMemory_marksTheRowComputedWithoutMetrics() = runBlocking {
        val huge = insert(1, track = "huge")
        val normal = insert(2)

        val report = backfill(derive = { input ->
            if (input.trackJson == "huge") throw OutOfMemoryError("track too big") else derive(input)
        }).runOnce()

        assertEquals(1, report.computed)
        assertEquals(1, report.unreadable)
        assertEquals(WorkoutDerivation.CURRENT_METRICS_VERSION, row(huge).metricsVersion)
        assertNull(row(huge).avgCadence)
        assertComputedByOnePass(listOf(normal))
    }

    @Test
    fun editBetweenReadAndWrite_isNotOverwritten() = runBlocking {
        val id = insert(1)
        val form = WorkoutStore(database, derive = { Derived(avgCadence = 999f, efforts = emptyList()) })

        val report = backfill(derive = { input ->
            // The form saves the row while the pass is computing it
            runBlocking { form.saveEdited(row(id).copy(notes = "edited")) }
            derive(input)
        }).runOnce()

        assertEquals(0, report.computed)
        assertEquals(1, report.skippedEdited)
        val row = row(id)
        assertEquals("edited", row.notes)
        assertEquals(999f, row.avgCadence!!, 0f)
        assertTrue("the pass leaves the edit's efforts alone", efforts.getForWorkout(id).isEmpty())
    }

    @Test
    fun raisedVersion_recomputesEveryRow() = runBlocking {
        val ids = (1..3).map { insert(it, version = 1) }

        assertEquals(0, backfill(version = 1).runOnce().computed)
        val report = backfill(version = 2).runOnce()

        assertEquals(3, report.computed)
        assertComputedByOnePass(ids, version = 2)
    }

    @Test
    fun activeWorkout_pausesThePass() = runBlocking {
        val ids = (1..3).map { insert(it) }
        var active = true

        val paused = backfill(isWorkoutActive = { active }).runOnce()

        assertTrue(paused.pausedForWorkout)
        assertEquals(0, paused.computed)
        assertEquals(3, workouts.countWithMetricsBelow(WorkoutDerivation.CURRENT_METRICS_VERSION))

        active = false
        val resumed = backfill(isWorkoutActive = { active }).runOnce()

        assertFalse(resumed.pausedForWorkout)
        assertComputedByOnePass(ids)
    }

    @Test
    fun progress_countsRowsAndEndsDone() = runBlocking {
        (1..3).map { insert(it) }
        val seen = mutableListOf<BackfillState>()
        lateinit var pass: MetricsBackfill
        pass = backfill(derive = { input ->
            seen += pass.progress.value
            derive(input)
        })

        assertEquals(BackfillState.Idle, pass.progress.value)
        pass.runOnce()

        assertEquals(
            listOf(BackfillState.Running(0, 3), BackfillState.Running(1, 3), BackfillState.Running(2, 3)),
            seen
        )
        // Not after an import (a raised version, the app start): the fake efforts hold the 1 km
        assertEquals(BackfillState.Done(changedDistances = 1, afterImport = false), pass.progress.value)
    }

    private fun effort(workoutId: Long, meters: Int, elapsedMs: Long) =
        BestEffort(workoutId, meters, elapsedMs, 0L, elapsedMs, 0f)

    /** A row saved computed meanwhile (live recording, the form), with its [bestEfforts]. */
    private suspend fun insertComputed(n: Int, vararg bestEfforts: Pair<Int, Long>): Long {
        val id = insert(n, version = WorkoutDerivation.CURRENT_METRICS_VERSION)
        efforts.replaceForWorkout(id, bestEfforts.map { (meters, ms) -> effort(id, meters, ms) })
        return id
    }

    /** Efforts by the row's duration in minutes: row n → [byMinutes] (n). */
    private fun deriveBy(byMinutes: Map<Int, List<Effort>>): (DerivationInput) -> Derived = { input ->
        Derived(efforts = byMinutes[(input.durationMs / 60_000L).toInt()].orEmpty())
    }

    private fun e(meters: Int, ms: Long) = Effort(meters, ms, 0L, ms, 0f)

    @Test
    fun done_countsTheDistancesWhoseRecordTheImportHolds() = runBlocking {
        insertComputed(1, 1_000 to 300_000L, 5_000 to 1_600_000L)
        // An import of two runs: a faster 1 km and a slower 5 km, then a first half marathon
        val imported = listOf(insert(2), insert(3))
        val pass = backfill(
            derive = deriveBy(
                mapOf(2 to listOf(e(1_000, 290_000L), e(5_000, 1_700_000L)), 3 to listOf(e(21_097, 7_000_000L)))
            )
        )
        pass.markImported(imported)

        val report = pass.runOnce()

        assertEquals(2, report.changedDistances)
        assertEquals(BackfillState.Done(changedDistances = 2, afterImport = true), pass.progress.value)
    }

    @Test
    fun aRunSavedWhileThePassWaits_isNotCountedForTheImport() = runBlocking {
        val imported = listOf(insert(1), insert(2))
        var active = false
        val pass = backfill(
            derive = { input ->
                active = true // a workout starts after the first row
                deriveBy(mapOf(1 to listOf(e(1_000, 300_000L)), 2 to listOf(e(1_000, 320_000L))))(input)
            },
            isWorkoutActive = { active }
        )
        pass.markImported(imported)

        assertTrue(pass.runOnce().pausedForWorkout)
        // The workout is saved: a first 5 km and a faster 1 km than the import's
        insertComputed(9, 1_000 to 250_000L, 5_000 to 1_500_000L)
        active = false
        val report = pass.runOnce()

        assertEquals(0, report.changedDistances)
        assertEquals(BackfillState.Done(changedDistances = 0, afterImport = true), pass.progress.value)
    }

    @Test
    fun aRaisedVersion_isNoImport() = runBlocking {
        // Every row computed by an older version: records are rebuilt, not "updated by an import"
        (1..2).forEach { insert(it, version = WorkoutDerivation.CURRENT_METRICS_VERSION - 1) }
        val pass = backfill()

        val report = pass.runOnce()

        assertEquals(BackfillState.Done(changedDistances = 1, afterImport = false), pass.progress.value)
        assertEquals(1, report.changedDistances)
    }

    @Test
    fun anExclusionDuringThePass_spoilsNoLaterCount() = runBlocking {
        val first = listOf(insert(1), insert(2))
        var active = false
        var derived = 0
        val pass = backfill(
            derive = { input ->
                if (++derived == 1) active = true // a workout starts after the first row
                deriveBy(
                    mapOf(1 to listOf(e(1_000, 300_000L)), 2 to listOf(e(1_000, 280_000L)), 3 to listOf(e(1_000, 310_000L)))
                )(input)
            },
            isWorkoutActive = { active }
        )
        pass.markImported(first)

        assertTrue(pass.runOnce().pausedForWorkout) // the faster row 2 is computed
        workouts.setExcludeFromRecords(first[1], true)
        active = false
        // Row 1 holds the 1 km now: still the import's
        assertEquals(1, pass.runOnce().changedDistances)

        // A later import of a slower run changes nothing
        val second = listOf(insert(3))
        pass.markImported(second)
        val report = pass.runOnce()
        assertFalse(report.pausedForWorkout)
        assertEquals(0, report.changedDistances)
        assertEquals(BackfillState.Done(changedDistances = 0, afterImport = true), pass.progress.value)
    }

    @Test
    fun theImportBatch_isReportedOnce() = runBlocking {
        val pass = backfill()
        pass.markImported(listOf(insert(1)))

        val report = pass.runOnce()

        assertEquals(1, report.changedDistances)
        assertEquals(BackfillState.Done(changedDistances = 1, afterImport = true), pass.progress.value)
        // Spent: the next pass with nothing to do is idle
        pass.runOnce()
        assertEquals(BackfillState.Idle, pass.progress.value)
    }

    @Test
    fun start_runsInTheBackgroundAndPicksUpRowsAddedLater() = runBlocking {
        val first = insert(1)
        val pass = backfill()

        pass.start()
        withTimeout(10_000L) { pass.progress.first { it is BackfillState.Done } }
        val later = insert(2)
        pass.start()
        withTimeout(10_000L) {
            while (workouts.countWithMetricsBelow(WorkoutDerivation.CURRENT_METRICS_VERSION) > 0) {
                kotlinx.coroutines.delay(10L)
            }
        }

        assertComputedByOnePass(listOf(first, later))
    }
}
