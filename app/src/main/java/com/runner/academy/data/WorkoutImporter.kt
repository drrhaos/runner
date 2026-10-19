package com.runner.academy.data

import android.util.Log
import com.runner.academy.util.NotRunningException
import com.runner.academy.util.TrackFileImport
import com.runner.academy.util.WorkoutBackupFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.Reader

/** A file to import: its name and how to open it (null when it cannot be). */
class ImportFile(val name: String?, val open: () -> InputStream?)

/**
 * How far an import got: [done] of [total] files or backup rows (0 total: not known yet) and
 * what came of them. Every field has a default, so it is saved and read back as JSON.
 */
data class ImportTotals(
    val done: Int = 0,
    val total: Int = 0,
    val insertedIds: List<Long> = emptyList(),
    val pending: Int = 0,
    val duplicates: Int = 0,
    /** Files of another sport than running, skipped. */
    val notRunning: Int = 0,
    /** Files that are no GPX or TCX the app reads. */
    val failed: Int = 0,
    /** Files the app may not read any more (access lost after the process was killed). */
    val inaccessible: Int = 0
) {
    fun plus(batch: ImportBatch, rows: Int) = copy(
        done = done + rows,
        insertedIds = insertedIds + batch.insertedIds,
        pending = pending + batch.pending,
        duplicates = duplicates + batch.duplicates
    )
}

/**
 * What an import did: [imported] new workouts, [pending] waiting for a decision, [duplicates]
 * already there, [notRunning] files of another sport, [failed] unreadable files,
 * [inaccessible] files the app lost access to;
 * [changedRecordDistances] for files (a backup's records come from the background pass).
 */
data class ImportResult(
    val imported: Int,
    val pending: Int,
    val duplicates: Int,
    val notRunning: Int,
    val failed: Int,
    val inaccessible: Int,
    val changedRecordDistances: Int?
)

/**
 * Imports workouts in batches ([WorkoutRepository.importBatch]), each in its own transaction:
 * memory holds one batch, and an interrupted import keeps what it wrote. Started again with the
 * [ImportTotals] it got to, it continues after them; anything it saved meanwhile is a copy and
 * skipped. [onBatch] gets the totals after each batch, to be kept for such a restart.
 */
class WorkoutImporter(private val repository: WorkoutRepository) {

    /** Imports [files] in their order, after the first [from].done of them. */
    suspend fun importFiles(
        files: List<ImportFile>,
        from: ImportTotals = ImportTotals(),
        onBatch: suspend (ImportTotals) -> Unit = {}
    ): ImportResult {
        var totals = from.copy(total = files.size)
        onBatch(totals)
        files.drop(totals.done).chunked(FILE_BATCH).forEach { chunk ->
            var notRunning = 0
            var failed = 0
            var inaccessible = 0
            val candidates = withContext(Dispatchers.IO) {
                chunk.mapNotNull { file ->
                    ensureActive()
                    when (val read = read(file)) {
                        is Read.Workout -> read.candidate
                        Read.NotRunning -> {
                            notRunning++
                            null
                        }
                        Read.Failed -> {
                            failed++
                            null
                        }
                        Read.NoAccess -> {
                            inaccessible++
                            null
                        }
                    }
                }
            }
            totals = totals.plus(repository.importBatch(candidates, WorkoutStore.Mode.INLINE), chunk.size)
                .let {
                    it.copy(
                        notRunning = it.notRunning + notRunning,
                        failed = it.failed + failed,
                        inaccessible = it.inaccessible + inaccessible
                    )
                }
            onBatch(totals)
        }
        return resultOf(totals, changedRecordDistances = repository.recordDistancesHeldBy(totals.insertedIds))
    }

    /** Imports a JSON backup read from [open] as it goes, after its first [from].done rows. */
    suspend fun importBackup(
        open: () -> Reader,
        from: ImportTotals = ImportTotals(),
        onBatch: suspend (ImportTotals) -> Unit = {}
    ): ImportResult = withContext(Dispatchers.IO) {
        var totals = from
        open().use { reader ->
            val batches = WorkoutBackupFormat.backupBatches(reader, BACKUP_BATCH, skip = from.done) { count ->
                totals = totals.copy(total = count)
            }
            for (batch in batches) {
                ensureActive()
                totals = totals.plus(repository.importBatch(batch.map { ImportCandidate(it) }, WorkoutStore.Mode.DEFERRED), batch.size)
                onBatch(totals)
            }
        }
        resultOf(totals, changedRecordDistances = null)
    }

    private fun resultOf(totals: ImportTotals, changedRecordDistances: Int?) = ImportResult(
        imported = totals.insertedIds.size,
        pending = totals.pending,
        duplicates = totals.duplicates,
        notRunning = totals.notRunning,
        failed = totals.failed,
        inaccessible = totals.inaccessible,
        changedRecordDistances = changedRecordDistances
    )

    private sealed interface Read {
        class Workout(val candidate: ImportCandidate) : Read
        data object NotRunning : Read
        data object Failed : Read
        data object NoAccess : Read
    }

    private fun read(file: ImportFile): Read = try {
        val workout = file.open()?.use { TrackFileImport.parse(it, file.name) }
            ?: throw IllegalStateException("Cannot open ${file.name}")
        Read.Workout(ImportCandidate(workout, source = file.name))
    } catch (e: NotRunningException) {
        Log.i(TAG, "Skip ${file.name}: not a run")
        Read.NotRunning
    } catch (e: SecurityException) {
        Log.w(TAG, "No access to ${file.name}: ${e.message}")
        Read.NoAccess
    } catch (e: Exception) {
        Log.w(TAG, "Skip track file ${file.name}: ${e.message}")
        Read.Failed
    }

    companion object {
        private const val TAG = "WorkoutImport"

        /** Files per transaction: a few dozen tracks in memory at once. */
        const val FILE_BATCH = 25

        /** Backup rows per transaction (saved uncomputed: small). */
        const val BACKUP_BATCH = 200
    }
}
