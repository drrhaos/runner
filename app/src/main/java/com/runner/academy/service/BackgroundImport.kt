package com.runner.academy.service

import android.content.Context
import androidx.core.content.edit
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.runner.academy.data.ImportResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.UUID

/** The latest import, as the screen shows it. */
sealed interface ImportState {
    /** Running: [done] of [total] read and written; 0 of 0 while listing. */
    data class Running(val done: Int, val total: Int) : ImportState

    /** Ended; [id] tells apart a result already shown ([BackgroundImport.markShown]). */
    sealed interface Ended : ImportState {
        val id: UUID
    }

    data class Finished(override val id: UUID, val result: ImportResult) : Ended

    /** A folder without GPX or TCX files. */
    data class NoFiles(override val id: UUID) : Ended

    /** Stopped on an error; the batches before it are saved. */
    data class Failed(override val id: UUID, val message: String) : Ended
}

/**
 * Workout imports in WorkManager ([ImportWorker]): an import goes on when the user leaves the
 * screen, and after the process was killed WorkManager runs it again from where it got to
 * ([ImportJob]). One import at a time.
 */
object BackgroundImport {

    private const val NAME = "workout_import"
    private const val PREFS = "import_work"
    private const val KEY_SHOWN = "shown_id"

    internal const val KEY_JOB = "job"
    internal const val KEY_DONE = "done"
    internal const val KEY_TOTAL = "total"
    private const val KEY_IMPORTED = "imported"
    private const val KEY_PENDING = "pending"
    private const val KEY_DUPLICATES = "duplicates"
    private const val KEY_FAILED = "failed"
    private const val KEY_INACCESSIBLE = "inaccessible"
    private const val KEY_HAS_RECORDS = "has_records"
    private const val KEY_RECORDS = "records"
    internal const val KEY_NO_FILES = "no_files"
    internal const val KEY_ERROR = "error"

    /**
     * Starts importing [request]; false (nothing started) while an import runs. The check and
     * the start are not one step, but a second start meanwhile is ignored by WorkManager (KEEP).
     */
    suspend fun start(context: Context, request: ImportRequest): Boolean = withContext(Dispatchers.IO) {
        val workManager = WorkManager.getInstance(context)
        if (workManager.getWorkInfosForUniqueWork(NAME).get().any { !it.state.isFinished }) return@withContext false
        ImportJobs.finishAll(context)
        val job = ImportJobs.create(context, request)
        val work = OneTimeWorkRequestBuilder<ImportWorker>()
            .setInputData(workDataOf(KEY_JOB to job.path))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        workManager.enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP, work)
        true
    }

    /** Stops the running import; what it saved stays (its job is finished by the next start). */
    fun stop(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(NAME)
    }

    /** The latest import; null when none ran, it was stopped, or its record was pruned. */
    fun observe(context: Context): Flow<ImportState?> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(NAME).map { infos ->
            infos.lastOrNull()?.let(::stateOf)
        }

    /** The end of [id] was shown: a screen opened later does not show it again. */
    fun markShown(context: Context, id: UUID) {
        prefs(context).edit { putString(KEY_SHOWN, id.toString()) }
    }

    fun wasShown(context: Context, id: UUID): Boolean = prefs(context).getString(KEY_SHOWN, null) == id.toString()

    internal fun stateOf(info: WorkInfo): ImportState? = when (info.state) {
        WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> ImportState.Running(0, 0)
        WorkInfo.State.RUNNING -> ImportState.Running(info.progress.getInt(KEY_DONE, 0), info.progress.getInt(KEY_TOTAL, 0))
        WorkInfo.State.SUCCEEDED -> if (info.outputData.getBoolean(KEY_NO_FILES, false)) {
            ImportState.NoFiles(info.id)
        } else {
            ImportState.Finished(info.id, resultOf(info.outputData))
        }
        WorkInfo.State.FAILED -> ImportState.Failed(info.id, info.outputData.getString(KEY_ERROR) ?: "")
        // Stopped by the user: what it saved stays, there is nothing to report
        WorkInfo.State.CANCELLED -> null
    }

    internal fun dataOf(result: ImportResult): Data = workDataOf(
        KEY_IMPORTED to result.imported,
        KEY_PENDING to result.pending,
        KEY_DUPLICATES to result.duplicates,
        KEY_FAILED to result.failed,
        KEY_INACCESSIBLE to result.inaccessible,
        // Data holds no null
        KEY_HAS_RECORDS to (result.changedRecordDistances != null),
        KEY_RECORDS to (result.changedRecordDistances ?: 0)
    )

    internal fun resultOf(data: Data) = ImportResult(
        imported = data.getInt(KEY_IMPORTED, 0),
        pending = data.getInt(KEY_PENDING, 0),
        duplicates = data.getInt(KEY_DUPLICATES, 0),
        failed = data.getInt(KEY_FAILED, 0),
        inaccessible = data.getInt(KEY_INACCESSIBLE, 0),
        changedRecordDistances = if (data.getBoolean(KEY_HAS_RECORDS, false)) data.getInt(KEY_RECORDS, 0) else null
    )

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
