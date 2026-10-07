package com.runner.academy.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.runner.academy.gpsreplay.SyntheticRun
import com.runner.academy.util.RouteTimeAligner
import com.runner.academy.util.TrackDataJson
import com.runner.academy.util.WorkoutTrackRebuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Date

/**
 * Records through every write path with the real derivation (ticket 06 triggers): live
 * recording and form inline, GPX inline, backup import via the background pass; deletion,
 * exclusion and a date change act on the next read.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RecordsRepositoryTest {

    private lateinit var database: WorkoutDatabase
    private lateinit var repository: WorkoutRepository
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Before
    fun setUp() {
        database = WorkoutDatabase.getInMemoryDatabase(ApplicationProvider.getApplicationContext<Context>())
        repository = WorkoutRepository(database)
    }

    @After
    fun tearDown() {
        scope.cancel()
        database.close()
    }

    /** 2.5 km along a street at [speedMps] with GPS noise: a recorded run. */
    private fun track(speedMps: Double): String = TrackDataJson.toJson(
        RouteTimeAligner.buildTrackData(
            SyntheticRun(route = listOf(0.0 to 0.0, 2_500.0 to 0.0), speedMps = speedMps).rawPoints()
        )
    )

    private fun workout(day: Int, speedMps: Double, trackJson: String? = track(speedMps)) = Workout(
        date = Date(day * DAY_MS),
        distance = 2.5f,
        duration = (2_500 / speedMps * 1000).toLong(),
        movingDuration = (2_500 / speedMps * 1000).toLong(),
        avgPace = 5f,
        calories = null,
        notes = null,
        type = WorkoutType.EASY_RUN,
        trackData = trackJson
    )

    private suspend fun book() = repository.observeRecordBook().first()

    private suspend fun kmRecord() = book().current(RecordDistance.KM_1)

    private suspend fun kmHistory() = book().history(RecordDistance.KM_1).map { it.effort.workoutId }

    @Test
    fun aSavedRun_holdsItsRecordAtOnce() = runBlocking {
        val id = repository.insertWorkout(workout(day = 1, speedMps = 3.3))

        val record = kmRecord()!!
        assertEquals(id, record.effort.workoutId)
        assertEquals(1_000_000.0 / 3.3, record.effort.elapsedMs.toDouble(), 10_000.0)
        val card = repository.observeRecordCard(id, justSaved = true).first()!!
        assertEquals(RecordStatus.First, card.entries.single().status)
    }

    @Test
    fun aRouteTimedByTheForm_setsNoRecord() = runBlocking {
        val uniform = WorkoutTrackRebuilder.rebuild(null, track(3.3), null, Date(DAY_MS), 600_000L)
        assertEquals(WorkoutTrackRebuilder.TimeSource.UNIFORM, uniform.timeSource)

        val id = repository.insertWorkout(workout(day = 1, speedMps = 3.3, trackJson = uniform.trackDataJson))

        assertTrue(database.bestEffortDao().getForWorkout(id).isEmpty())
        assertNull(kmRecord())
    }

    @Test
    fun aManualWorkout_setsNoRecord() = runBlocking {
        repository.insertWorkout(workout(day = 1, speedMps = 3.3, trackJson = null))

        assertNull(kmRecord())
    }

    @Test
    fun aGpxImport_countsTheRecordsItChanged() = runBlocking {
        repository.insertWorkout(workout(day = 1, speedMps = 3.0))

        val imported = repository.importGpx(listOf(workout(day = 2, speedMps = 3.5), workout(day = 3, speedMps = 3.2)))

        assertEquals(2, imported.ids.size)
        assertEquals(1, imported.changedRecordDistances)
        assertEquals(imported.ids[0], kmRecord()!!.effort.workoutId)
    }

    @Test
    fun aBackupImport_getsItsRecordsFromTheBackgroundPass() = runBlocking {
        val ids = repository.importBackup(listOf(workout(day = 1, speedMps = 3.0), workout(day = 2, speedMps = 3.5)))
        assertNull(kmRecord())
        assertTrue(repository.observeRecordsPending().first())
        assertNull(repository.observeRecordCard(ids[1], justSaved = false).first())

        val report = MetricsBackfill(database, scope).runOnce()

        assertEquals(1, report.changedDistances)
        assertEquals(ids, kmHistory())
        assertEquals(false, repository.observeRecordsPending().first())
        assertEquals(RecordStatus.Current, repository.observeRecordCard(ids[1], justSaved = false).first()!!.entries.single().status)
    }

    @Test
    fun noCardWhileOlderRunsWaitForTheirMetrics() = runBlocking {
        // An older run with a track, saved before this version (e.g. right after an update)
        val older = workout(day = 1, speedMps = 3.5).copy(metricsVersion = 0)
        database.workoutDao().insertWorkout(older)

        val id = repository.insertWorkout(workout(day = 2, speedMps = 3.0))

        assertNull(repository.observeRecordCard(id, justSaved = true).first())
        // A manual run waiting for its metrics holds no records back
        database.workoutDao().deleteWorkout(database.workoutDao().getAllWorkouts().first().first { it.id != id })
        database.workoutDao().insertWorkout(workout(day = 1, speedMps = 3.5, trackJson = null).copy(metricsVersion = 0))
        assertNotNull(repository.observeRecordCard(id, justSaved = true).first())
    }

    @Test
    fun deletingOrExcludingTheRecord_fallsBackToTheNextBest() = runBlocking {
        val slow = repository.insertWorkout(workout(day = 1, speedMps = 3.0))
        val fast = repository.insertWorkout(workout(day = 2, speedMps = 3.5))
        assertEquals(fast, kmRecord()!!.effort.workoutId)

        repository.setExcludeFromRecords(fast, true)
        assertEquals(slow, kmRecord()!!.effort.workoutId)
        repository.setExcludeFromRecords(fast, false)
        assertEquals(fast, kmRecord()!!.effort.workoutId)

        repository.deleteWorkout(repository.getWorkoutById(fast).first()!!)
        assertEquals(slow, kmRecord()!!.effort.workoutId)
        assertTrue(database.bestEffortDao().getForWorkout(fast).isEmpty())
    }

    @Test
    fun aDateChange_reordersTheHistory() = runBlocking {
        val slow = repository.insertWorkout(workout(day = 1, speedMps = 3.0))
        val fast = repository.insertWorkout(workout(day = 2, speedMps = 3.5))
        assertEquals(listOf(slow, fast), kmHistory())

        // The fast run moved before the slow one: the slow one is no record any more
        val moved = repository.getWorkoutById(fast).first()!!
        val shifted = WorkoutTrackRebuilder.rebuild(moved.trackData, moved.trackData, moved.date, Date(0L), moved.duration)
        repository.saveEdited(moved.copy(date = Date(0L), trackData = shifted.trackDataJson))

        assertEquals(listOf(fast), kmHistory())
        assertEquals(RecordStatus.Current, repository.observeRecordCard(fast, justSaved = false).first()!!.entries.single().status)
        assertNull(repository.observeRecordCard(slow, justSaved = false).first())
    }

    private companion object {
        const val DAY_MS = 86_400_000L
    }
}
