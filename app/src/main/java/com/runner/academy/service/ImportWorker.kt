package com.runner.academy.service

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.runner.academy.appContainer
import com.runner.academy.data.ImportFile
import com.runner.academy.data.ImportTotals
import com.runner.academy.data.WorkoutImporter
import kotlinx.coroutines.CancellationException
import java.io.File

/**
 * Imports one [ImportJob] in the foreground (a long import outlasts a background worker's
 * 10 min), saving the job after each batch so a run after a kill continues from there.
 */
class ImportWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val notifications = ImportNotifications(applicationContext)

    override suspend fun getForegroundInfo(): ForegroundInfo = notifications.progress(id, 0, 0)

    override suspend fun doWork(): Result {
        val lost = Result.failure(workDataOf(BackgroundImport.KEY_ERROR to "import job lost"))
        val file = inputData.getString(BackgroundImport.KEY_JOB)?.let(::File) ?: return lost
        val job = ImportJobs.read(file) ?: return lost
        val inForeground = try {
            setForeground(getForegroundInfo())
            true
        } catch (e: IllegalStateException) {
            // Not allowed in the foreground now (a run again from the background): it goes on as a
            // background worker, without a progress notification that nothing would remove
            Log.w(TAG, "Import not in the foreground: ${e.message}")
            false
        }
        val importer = WorkoutImporter(applicationContext.appContainer().workoutRepository)
        val onBatch: suspend (ImportTotals) -> Unit = { totals ->
            ImportJobs.save(file, job.copy(totals = totals))
            setProgress(workDataOf(BackgroundImport.KEY_DONE to totals.done, BackgroundImport.KEY_TOTAL to totals.total))
            if (inForeground) notifications.updateProgress(id, totals.done, totals.total)
        }
        return try {
            val request = job.request
            val result = when (request.kind) {
                ImportRequest.Kind.FILES -> importer.importFiles(files(request.uris.map { it.toUri() }), job.totals, onBatch)
                ImportRequest.Kind.FOLDER -> {
                    val files = folderFiles(request.uris.single().toUri())
                    if (files.isEmpty()) {
                        ImportJobs.finish(applicationContext, file)
                        return Result.success(workDataOf(BackgroundImport.KEY_NO_FILES to true))
                    }
                    importer.importFiles(files, job.totals, onBatch)
                }
                ImportRequest.Kind.BACKUP -> importer.importBackupFile(files(request.uris.map { it.toUri() }).single(), job.totals, onBatch)
            }
            ImportJobs.finish(applicationContext, file)
            notifications.finished(result)
            Result.success(BackgroundImport.dataOf(result))
        } catch (e: CancellationException) {
            // Stopped: by the user, or by the system (then WorkManager runs it again: the job stays)
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Import failed: ${e.message}", e)
            ImportJobs.finish(applicationContext, file)
            Result.failure(workDataOf(BackgroundImport.KEY_ERROR to (e.message ?: e.javaClass.simpleName)))
        } finally {
            notifications.cancelProgress()
        }
    }

    private fun files(uris: List<Uri>): List<ImportFile> =
        uris.mapNotNull { DocumentFile.fromSingleUri(applicationContext, it) }.map(::importFile)

    /**
     * The folder's files in a stable order (a run again continues after the same ones). Any
     * name is read, its content tells what it is (a GPX may come as a `.txt`); photos, videos
     * and sound are not even opened.
     */
    private fun folderFiles(treeUri: Uri): List<ImportFile> {
        val root = DocumentFile.fromTreeUri(applicationContext, treeUri)
            ?: throw IllegalStateException("Cannot open folder")
        return collectFiles(root).sortedBy { it.uri.toString() }.map(::importFile)
    }

    private fun importFile(document: DocumentFile) =
        ImportFile(document.name) { applicationContext.contentResolver.openInputStream(document.uri) }

    private fun collectFiles(dir: DocumentFile): List<DocumentFile> {
        val result = mutableListOf<DocumentFile>()
        dir.listFiles().forEach { child ->
            when {
                child.isDirectory -> result.addAll(collectFiles(child))
                child.isFile && !ImportFolder.isMedia(child.type) -> result.add(child)
            }
        }
        return result
    }

    private companion object {
        const val TAG = "ImportWorker"
    }
}
