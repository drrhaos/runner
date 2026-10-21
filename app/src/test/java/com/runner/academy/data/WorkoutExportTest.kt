package com.runner.academy.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.runner.academy.ui.workout.WorkoutViewModel
import com.runner.academy.util.TrackDataJson
import com.runner.academy.util.WorkoutBackupFormat
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Date

/** "Export all": the rows are read a page at a time and the backup is written as they come. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WorkoutExportTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var database: WorkoutDatabase
    private lateinit var repository: WorkoutRepository

    @Before
    fun setUp() {
        database = WorkoutDatabase.getInMemoryDatabase(ApplicationProvider.getApplicationContext<Context>())
        repository = WorkoutRepository(database)
    }

    @After
    fun tearDown() = database.close()

    private fun track(start: Long) = TrackDataJson.toJson(
        TrackData(
            points = (0 until 20).map { i -> TrackPoint(55.75 + i * 1e-4, 37.61, start + i * 1_000L, 5f, 3f, 150.0) },
            totalDistance = 220f, totalDuration = 20_000L, avgSpeed = 3f, maxSpeed = 3f,
            startTime = start, endTime = start + 20_000L
        )
    )

    /** [count] workouts a day apart, every other with a track; returns them as saved. */
    private suspend fun saved(count: Int): List<Workout> {
        val workouts = (1..count).map { n ->
            val start = n * 86_400_000L
            Workout(
                date = Date(start), distance = 5f, duration = 1_800_000L, movingDuration = 1_800_000L,
                avgPace = 6f, calories = null, notes = "run $n", type = WorkoutType.EASY_RUN,
                trackData = if (n % 2 == 0) track(start) else null
            )
        }
        database.workoutDao().insertWorkouts(workouts)
        return workouts
    }

    @Test
    fun workoutsForExport_goesThroughEveryPage_newestFirst_passingOverDeletedRows() = runBlocking {
        saved(WorkoutRepository.EXPORT_PAGE * 2 + 3)
        val ids = repository.workoutIdsNewestFirst()
        // Deleted between the snapshot of ids and the read of its page
        database.workoutDao().deleteWorkouts(listOf(ids[WorkoutRepository.EXPORT_PAGE + 1]))

        val exported = repository.workoutsForExport(ids).toList()

        assertEquals(ids.size - 1, exported.size)
        assertEquals(exported.map { it.date }.sortedDescending(), exported.map { it.date })
        assertEquals(ids - ids[WorkoutRepository.EXPORT_PAGE + 1], exported.map { it.id })
    }

    @Test
    fun aPeriod_takesTheWorkoutsStartedInIt_bothEndsIncluded() = runBlocking {
        saved(10)
        val day = 86_400_000L
        // Days 3..5: from the first millisecond of day 3 to the last of day 5
        val period = ExportPeriod(fromMs = 3 * day, toMs = 6 * day - 1)

        val exported = repository.workoutsForExport(repository.workoutIdsNewestFirst(period)).toList()

        assertEquals(listOf("run 5", "run 4", "run 3"), exported.map { it.notes })
    }

    @Test
    fun writeBackupJson_writesEveryWorkoutWithItsTrack_readBackByTheImport() = runBlocking {
        val workouts = saved(WorkoutRepository.EXPORT_PAGE * 3)
        val viewModel = WorkoutViewModel(repository)

        val file = viewModel.writeBackupJson(viewModel.exportIds(), folder.root, "test")
        var declared = -1
        val read = file.bufferedReader().use { reader ->
            WorkoutBackupFormat.backupBatches(reader, batchSize = 7) { declared = it }.flatten().toList()
        }

        assertEquals(workouts.size, declared)
        assertEquals(workouts.map { it.notes }.reversed(), read.map { it.notes })
        assertEquals(
            workouts.reversed().map { TrackDataJson.parse(it.trackData)?.points },
            read.map { TrackDataJson.parse(it.trackData)?.points }
        )
        assertTrue(folder.root.listFiles()!!.single().name.startsWith("runner_workouts_backup_"))
    }
}
