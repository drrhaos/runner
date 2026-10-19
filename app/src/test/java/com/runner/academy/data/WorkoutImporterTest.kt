package com.runner.academy.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.runner.academy.util.WorkoutBackupFormat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.StringReader
import java.util.Date

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WorkoutImporterTest {

    private lateinit var database: WorkoutDatabase
    private lateinit var repository: WorkoutRepository
    private lateinit var importer: WorkoutImporter

    @Before
    fun setUp() {
        database = WorkoutDatabase.getInMemoryDatabase(ApplicationProvider.getApplicationContext<Context>())
        repository = WorkoutRepository(database)
        importer = WorkoutImporter(repository)
    }

    @After
    fun tearDown() = database.close()

    /** A 10 min GPX run on day [day]. */
    private fun gpx(day: Int): String {
        val start = "2024-01-%02dT10".format(day)
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1"><trk><trkseg>
              <trkpt lat="55.7558" lon="37.6176"><time>$start:00:00Z</time></trkpt>
              <trkpt lat="55.7608" lon="37.6176"><time>$start:05:00Z</time></trkpt>
              <trkpt lat="55.7658" lon="37.6176"><time>$start:10:00Z</time></trkpt>
            </trkseg></trk></gpx>
        """.trim()
    }

    private fun file(name: String, text: String?) = ImportFile(name) { text?.byteInputStream() }

    private suspend fun savedCount() = repository.getAllWorkouts().first().size

    @Test
    fun files_areImportedInBatches_withProgress() = runBlocking {
        val files = (1..WorkoutImporter.FILE_BATCH + 3).map { file("run$it.gpx", gpx(it)) }
        val progress = mutableListOf<Pair<Int, Int>>()

        val result = importer.importFiles(files) { progress += it.done to it.total }

        assertEquals(files.size, result.imported)
        assertEquals(files.size, savedCount())
        assertEquals(listOf(0 to 28, 25 to 28, 28 to 28), progress)
        assertEquals("new runs hold the records", true, (result.changedRecordDistances ?: 0) > 0)
    }

    @Test
    fun anUnreadableFile_isCounted_theOthersImported() = runBlocking {
        val result = importer.importFiles(
            listOf(file("a.gpx", gpx(1)), file("broken.gpx", "<gpx>"), file("gone.gpx", null), file("b.gpx", gpx(2)))
        )

        assertEquals(2, result.imported)
        assertEquals(2, result.failed)
    }

    @Test
    fun anotherSport_isCountedApartFromErrors() = runBlocking {
        val ride = gpx(2).replace("<trk>", "<trk><type>Ride</type>")

        val result = importer.importFiles(listOf(file("a.gpx", gpx(1)), file("ride.gpx", ride)))

        assertEquals(1, result.imported)
        assertEquals(1, result.notRunning)
        assertEquals(0, result.failed)
        assertEquals(1, savedCount())
    }

    @Test
    fun aFileWithoutAccess_isCountedApart() = runBlocking {
        val result = importer.importFiles(
            listOf(file("a.gpx", gpx(1)), ImportFile("lost.gpx") { throw SecurityException("no grant") })
        )

        assertEquals(1, result.imported)
        assertEquals(0, result.failed)
        assertEquals(1, result.inaccessible)
    }

    @Test
    fun restartedFromItsTotals_continuesAfterThem_andReportsTheWhole() = runBlocking {
        val files = (1..WorkoutImporter.FILE_BATCH + 5).map { file("run$it.gpx", gpx(it)) }
        var afterFirstBatch: ImportTotals? = null
        // The process dies right after the first batch was saved
        runCatching {
            importer.importFiles(files) { totals ->
                if (totals.done > 0) {
                    afterFirstBatch = totals
                    throw IllegalStateException("killed")
                }
            }
        }
        val read = mutableListOf<String>()
        val counted = files.map { f -> ImportFile(f.name) { read += f.name!!; f.open() } }

        val result = importer.importFiles(counted, from = afterFirstBatch!!)

        assertEquals("only the files after the first batch are read again", 5, read.size)
        assertEquals(files.size, result.imported)
        assertEquals(files.size, savedCount())
    }

    @Test
    fun runAgain_skipsWhatTheFirstRunSaved() = runBlocking {
        importer.importFiles(listOf(file("a.gpx", gpx(1))))

        val again = importer.importFiles(listOf(file("a.gpx", gpx(1)), file("b.gpx", gpx(2))))

        assertEquals(1, again.imported)
        assertEquals(1, again.duplicates)
        assertEquals(2, savedCount())
    }

    @Test
    fun backup_isImportedInBatches_recordsLeftToTheBackgroundPass() = runBlocking {
        val workouts = (0 until WorkoutImporter.BACKUP_BATCH + 1).map { n ->
            Workout(
                date = Date(n * 86_400_000L), distance = 5f, duration = 1_800_000L, movingDuration = 1_800_000L,
                avgPace = 6f, calories = null, notes = null, type = WorkoutType.EASY_RUN
            )
        }
        val json = WorkoutBackupFormat.toBackupJson(workouts, "test")
        val progress = mutableListOf<Pair<Int, Int>>()

        val result = importer.importBackup(open = { StringReader(json) }) { progress += it.done to it.total }

        assertEquals(workouts.size, result.imported)
        assertEquals("the declared count is the total", listOf(200 to 201, 201 to 201), progress)
        assertNull(result.changedRecordDistances)

        val again = importer.importBackup(open = { StringReader(json) }, from = ImportTotals(done = 200))
        assertEquals("a restart reads only the rows after", 0, again.imported)
        assertEquals(1, again.duplicates)
    }
}
