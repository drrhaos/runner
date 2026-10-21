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
import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.util.Date
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

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

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }.toByteArray()

    private fun backupJson(days: IntRange): String = WorkoutBackupFormat.toBackupJson(
        days.map { n ->
            Workout(
                date = Date(n * 86_400_000L), distance = 5f, duration = 1_800_000L, movingDuration = 1_800_000L,
                avgPace = 6f, calories = null, notes = null, type = WorkoutType.EASY_RUN
            )
        },
        "test"
    )

    @Test
    fun anArchive_isReadEntryByEntry_withProgressByCount() = runBlocking {
        val runs = (1..WorkoutImporter.FILE_BATCH + 2).map { "activities/$it.gpx" to gpx(it).toByteArray() }
        val export = zip(*runs.toTypedArray(), "media/photo.jpg" to byteArrayOf(1, 2, 3))
        val progress = mutableListOf<Pair<Int, Int>>()

        val result = importer.importFiles(listOf(ImportFile("export.zip") { export.inputStream() })) {
            progress += it.done to it.total
        }

        assertEquals(runs.size, result.imported)
        assertEquals(0, result.failed)
        assertEquals("a file count first, then units counted with no total", listOf(0 to 1, 25 to 0, 28 to 0), progress)
    }

    @Test
    fun anArchiveRunAgain_passesOverWhatWasSaved() = runBlocking {
        val runs = (1..WorkoutImporter.FILE_BATCH + 3).map { "$it.gpx" to gpx(it).toByteArray() }
        val export = zip(*runs.toTypedArray())
        val files = listOf(file("a.gpx", gpx(29)), ImportFile("export.zip") { export.inputStream() })
        var saved: ImportTotals? = null
        runCatching {
            importer.importFiles(files) { totals ->
                if (totals.done > 0) {
                    saved = totals
                    throw IllegalStateException("killed")
                }
            }
        }

        val result = importer.importFiles(files, from = saved!!)

        assertEquals(runs.size + 1, result.imported)
        assertEquals("nothing saved before is read again", 0, result.duplicates)
        assertEquals(runs.size + 1, savedCount())
    }

    @Test
    fun aBackupInAnArchive_isImportedWithTheTracks() = runBlocking {
        val export = zip("runner_backup.json" to backupJson(0..2).toByteArray(), "run.gpx" to gpx(20).toByteArray())

        val result = importer.importFiles(listOf(ImportFile("export.zip") { export.inputStream() }))

        assertEquals(4, result.imported)
        assertEquals(4, savedCount())
    }

    @Test
    fun aBrokenBackupAmongFiles_isOneError_theRestImported() = runBlocking {
        val broken = """{"version":2,"workouts":[{"date":"""
        val export = zip("runner_backup.json" to broken.toByteArray(), "run.gpx" to gpx(21).toByteArray())

        val result = importer.importFiles(listOf(ImportFile("export.zip") { export.inputStream() }, file("b.gpx", gpx(22))))

        assertEquals(2, result.imported)
        assertEquals(1, result.failed)
    }

    @Test
    fun backupRowsAmongFiles_moveTheCount() = runBlocking {
        val export = zip("runner_backup.json" to backupJson(0..2).toByteArray())
        val counts = mutableListOf<Int>()

        importer.importFiles(listOf(ImportFile("export.zip") { export.inputStream() })) { counts += it.done }

        assertEquals("three rows, then the backup unit", 4, counts.last())
    }

    @Test
    fun aFolderFileThatIsNoWorkout_isPassedSilently() = runBlocking {
        val result = importer.importFiles(listOf(file("a.gpx", gpx(1)), file("notes.txt", "shopping list")))

        assertEquals(1, result.imported)
        assertEquals(0, result.failed)
    }

    @Test
    fun theBackupItem_readsABackupInAZipToo() = runBlocking {
        val zipped = zip("backup.json" to backupJson(0..4).toByteArray())

        val result = importer.importBackupFile(ImportFile("backup.zip") { zipped.inputStream() })

        assertEquals(5, result.imported)
    }

    @Test
    fun theBackupItem_readsAPlainBackupRowByRow() = runBlocking {
        val json = backupJson(0..4)
        val progress = mutableListOf<Pair<Int, Int>>()

        val result = importer.importBackupFile(ImportFile("backup.json") { json.byteInputStream() }) {
            progress += it.done to it.total
        }

        assertEquals(5, result.imported)
        assertEquals(listOf(5 to 5), progress)
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

    @Test
    fun backupRowsWithTracks_goInBatchesCutBySize_andAllArrive() = runBlocking {
        val workouts = (1..5).map { n ->
            val start = n * 86_400_000L
            val track = """{"points":[{"latitude":55.75,"longitude":37.61,"timestamp":$start},""" +
                """{"latitude":55.76,"longitude":37.61,"timestamp":${start + 600_000L}}],""" +
                """"total_distance":1100,"total_duration":600000,"avg_speed":1.8,"max_speed":2,"start_time":$start}"""
            Workout(
                date = Date(start), distance = 1.1f, duration = 600_000L, movingDuration = 600_000L,
                avgPace = 9f, calories = null, notes = null, type = WorkoutType.EASY_RUN, trackData = track
            )
        }
        val json = WorkoutBackupFormat.toBackupJson(workouts, "test")
        val progress = mutableListOf<Int>()
        // Any track fills a batch: one row per transaction
        val bySize = WorkoutImporter(repository, backupBatchChars = 1L)

        val result = bySize.importBackup(open = { StringReader(json) }) { progress += it.done }

        assertEquals(listOf(1, 2, 3, 4, 5), progress)
        assertEquals(5, result.imported)
        assertEquals(5, savedCount())
    }
}
