package com.runner.academy.data

import android.util.Log
import com.runner.academy.util.ImportFormat
import com.runner.academy.util.WorkoutBackupFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader

/** A file to import: its name and how to open it (null when it cannot be). */
class ImportFile(val name: String?, val open: () -> InputStream?)

/**
 * How far an import got: [done] of [total] import units — files, archive entries or backup rows
 * (0 total: not known, still listing or reading archives) — and what came of them. Every field
 * has a default, so it is saved and read back as JSON.
 */
data class ImportTotals(
    val done: Int = 0,
    val total: Int = 0,
    val insertedIds: List<Long> = emptyList(),
    val pending: Int = 0,
    val duplicates: Int = 0,
    /** Workouts of another sport than running, skipped. */
    val notRunning: Int = 0,
    /** Workout files the app could not read (broken, cut, too large). */
    val failed: Int = 0,
    /** Files the app may not read any more (access lost after the process was killed). */
    val inaccessible: Int = 0,
    /** Picked files read to their end: a run again opens the next one. */
    val filesDone: Int = 0,
    /** Units of the next file read already (an archive's entries): a run again passes them over. */
    val fileUnits: Int = 0,
    /** An archive was opened: units are counted with no total ([total] 0). */
    val archives: Boolean = false
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
class WorkoutImporter(
    private val repository: WorkoutRepository,
    private val walker: ImportWalker = ImportWalker()
) {

    /**
     * Imports [files] in their order — track files, archives of them, backups, told by content
     * ([ImportWalker]) — after the units [from] got to. A file of no workout is passed silently.
     */
    suspend fun importFiles(
        files: List<ImportFile>,
        from: ImportTotals = ImportTotals(),
        onBatch: suspend (ImportTotals) -> Unit = {}
    ): ImportResult = withContext(Dispatchers.IO) {
        var totals = if (from.archives) from else from.copy(total = files.size)
        onBatch(totals)
        val candidates = ArrayList<ImportCandidate>()
        // Read since the last save: units, and what came of those that are no candidate
        var units = 0
        var notRunning = 0
        var failed = 0
        var inaccessible = 0
        var archives = totals.archives
        var filesDone = totals.filesDone
        var fileUnits = totals.fileUnits

        // Saves what was read; the position it records is where a run again starts
        suspend fun save() {
            val batch = repository.importBatch(candidates.toList(), WorkoutStore.Mode.INLINE)
            totals = totals.plus(batch, units).copy(
                total = if (archives) 0 else totals.total,
                notRunning = totals.notRunning + notRunning,
                failed = totals.failed + failed,
                inaccessible = totals.inaccessible + inaccessible,
                filesDone = filesDone,
                fileUnits = fileUnits,
                archives = archives
            )
            candidates.clear()
            units = 0
            notRunning = 0
            failed = 0
            inaccessible = 0
            onBatch(totals)
        }

        // Checked before a unit is taken (and after a file): a save never falls inside a unit
        suspend fun saveWhenDue() {
            if (candidates.size >= FILE_BATCH || units >= UNIT_BATCH) save()
        }

        for (file in files.drop(filesDone)) {
            // A file that does not open is one unit, an error or no access
            val stream = try {
                file.open().also {
                    if (it == null) {
                        Log.w(TAG, "Cannot open ${file.name}")
                        failed++
                        units++
                    }
                }
            } catch (e: SecurityException) {
                Log.w(TAG, "No access to ${file.name}: ${e.message}")
                inaccessible++
                units++
                null
            }
            if (stream != null) {
                stream.use { input ->
                    for (read in walker.units(file.name, input, skip = fileUnits)) {
                        ensureActive()
                        if (read == ImportRead.Archive) {
                            archives = true
                            continue
                        }
                        saveWhenDue()
                        when (read) {
                            is ImportRead.Track -> candidates += read.candidate
                            is ImportRead.Backup -> {
                                // Its rows go in batches of their own; a run again reads it whole (copies dropped)
                                save()
                                val (after, whole) = importBackupRows(read, totals, onBatch)
                                totals = after
                                if (!whole) failed++
                            }
                            ImportRead.NotRunning -> notRunning++
                            ImportRead.Failed -> failed++
                            ImportRead.Ignored, ImportRead.Archive -> Unit
                        }
                        units++
                        fileUnits++
                    }
                }
            }
            filesDone++
            fileUnits = 0
            saveWhenDue()
        }
        if (units > 0 || candidates.isNotEmpty()) save()
        resultOf(totals, changedRecordDistances = repository.recordDistancesHeldBy(totals.insertedIds))
    }

    /**
     * The item "Import backup": a JSON backup is read row by row (a run again continues from
     * [from]); anything else (a ZIP holding the backup) is read as files.
     */
    suspend fun importBackupFile(
        file: ImportFile,
        from: ImportTotals = ImportTotals(),
        onBatch: suspend (ImportTotals) -> Unit = {}
    ): ImportResult {
        val format = withContext(Dispatchers.IO) {
            (file.open() ?: throw IllegalStateException("Cannot open ${file.name}")).use {
                ImportFormat.sniff(BufferedInputStream(it))
            }
        }
        if (format != ImportFormat.BACKUP) return importFiles(listOf(file), from, onBatch)
        return importBackup(
            open = {
                val stream = file.open() ?: throw IllegalStateException("Cannot open ${file.name}")
                InputStreamReader(stream, Charsets.UTF_8).buffered()
            },
            from,
            onBatch
        )
    }

    /**
     * The rows of a [backup] met among files, added to [totals]: they move the count shown, not
     * the position a run again starts from (the backup is one unit). False with the totals when
     * it broke off: the rows before stay, the backup is one error.
     */
    private suspend fun importBackupRows(
        backup: ImportRead.Backup,
        totals: ImportTotals,
        onBatch: suspend (ImportTotals) -> Unit
    ): Pair<ImportTotals, Boolean> {
        var result = totals
        try {
            for (batch in WorkoutBackupFormat.backupBatches(backup.reader, BACKUP_BATCH)) {
                currentCoroutineContext().ensureActive()
                val saved = repository.importBatch(batch.map { ImportCandidate(it) }, WorkoutStore.Mode.DEFERRED)
                result = result.plus(saved, rows = batch.size)
                onBatch(result)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Backup ${backup.name} broke off: ${e.message}")
            return result to false
        }
        return result to true
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

    companion object {
        private const val TAG = "WorkoutImport"

        /** Workouts per transaction: a few dozen tracks in memory at once. */
        const val FILE_BATCH = 25

        /**
         * Units read between saves at most, workouts or not: a run again after a kill does not
         * page through thousands of a big export's other files again.
         */
        const val UNIT_BATCH = 100

        /** Backup rows per transaction (saved uncomputed: small). */
        const val BACKUP_BATCH = 200
    }
}
