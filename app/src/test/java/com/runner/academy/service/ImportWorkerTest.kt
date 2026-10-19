package com.runner.academy.service

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import com.google.gson.Gson
import com.runner.academy.appContainer
import com.runner.academy.data.ImportResult
import com.runner.academy.data.ImportTotals
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ImportWorkerTest {

    private lateinit var context: Context
    private lateinit var dir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Robolectric runs no androidx.startup: the worker's notification needs WorkManager
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        dir = File(context.cacheDir, "import-worker-test").apply { deleteRecursively(); mkdirs() }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun gpxFile(name: String, day: Int): File = File(dir, name).apply {
        val start = "2024-02-%02dT10".format(day)
        writeText(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1"><trk><trkseg>
              <trkpt lat="55.7558" lon="37.6176"><time>$start:00:00Z</time></trkpt>
              <trkpt lat="55.7658" lon="37.6176"><time>$start:10:00Z</time></trkpt>
            </trkseg></trk></gpx>
            """.trim()
        )
    }

    private fun job(request: ImportRequest): File =
        File(dir, "job.json").apply { writeText(Gson().toJson(ImportJob(request))) }

    private fun run(job: File): ListenableWorker.Result = runBlocking {
        TestListenableWorkerBuilder<ImportWorker>(context)
            .setInputData(workDataOf(BackgroundImport.KEY_JOB to job.path))
            .build()
            .doWork()
    }

    @Test
    fun files_areImported_andTheJobFinished() = runBlocking {
        val before = context.appContainer().workoutRepository.getAllWorkouts().first().size
        val files = listOf(gpxFile("a.gpx", 1), gpxFile("b.gpx", 2)).map { Uri.fromFile(it).toString() }
        val job = job(ImportRequest(ImportRequest.Kind.FILES, files))

        val result = run(job)

        assertTrue(result.toString(), result is ListenableWorker.Result.Success)
        val output = BackgroundImport.resultOf(result.outputData)
        assertEquals(2, output.imported)
        assertEquals(before + 2, context.appContainer().workoutRepository.getAllWorkouts().first().size)
        assertFalse("the job file is removed", job.exists())
    }

    @Test
    fun aSavedJob_continuesAfterWhatItDid() = runBlocking {
        val repository = context.appContainer().workoutRepository
        val first = Uri.fromFile(gpxFile("c.gpx", 3)).toString()
        val second = Uri.fromFile(gpxFile("d.gpx", 4)).toString()
        // The process was killed after the first file was imported and the job saved
        val firstId = repository.importBatch(
            listOf(com.runner.academy.data.ImportCandidate(com.runner.academy.util.TrackFileImport.parse(File(dir, "c.gpx").readText()))),
            com.runner.academy.data.WorkoutStore.Mode.INLINE
        ).insertedIds
        val job = File(dir, "job.json").apply {
            writeText(Gson().toJson(ImportJob(ImportRequest(ImportRequest.Kind.FILES, listOf(first, second)), ImportTotals(done = 1, insertedIds = firstId))))
        }

        val output = BackgroundImport.resultOf(run(job).outputData)

        assertEquals("both counted, the first not read again", 2, output.imported)
        assertEquals(0, output.duplicates)
    }

    @Test
    fun aLostJob_fails() {
        val result = run(File(dir, "missing.json"))

        assertTrue(result is ListenableWorker.Result.Failure)
    }

    @Test
    fun anUnreadableBackup_failsWithItsMessage() {
        val job = job(ImportRequest(ImportRequest.Kind.BACKUP, listOf(Uri.fromFile(File(dir, "nope.json")).toString())))

        val result = run(job)

        assertTrue(result is ListenableWorker.Result.Failure)
        assertTrue(result.outputData.getString(BackgroundImport.KEY_ERROR)!!.isNotEmpty())
        assertFalse(job.exists())
    }

    @Test
    fun result_survivesWorkData() {
        val result = ImportResult(imported = 3, pending = 2, duplicates = 1, notRunning = 6, failed = 4, inaccessible = 5, changedRecordDistances = null)

        assertEquals(result, BackgroundImport.resultOf(BackgroundImport.dataOf(result)))
        assertEquals(result.copy(changedRecordDistances = 0), BackgroundImport.resultOf(BackgroundImport.dataOf(result.copy(changedRecordDistances = 0))))
    }
}
