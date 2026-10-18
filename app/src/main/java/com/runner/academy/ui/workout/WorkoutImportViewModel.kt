package com.runner.academy.ui.workout

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.runner.academy.data.ImportBatch
import com.runner.academy.data.ImportCandidate
import com.runner.academy.data.WorkoutRepository
import com.runner.academy.util.TrackFileImport
import com.runner.academy.util.WorkoutBackupFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream

/** A file to import: its name and how to open it (null when it cannot be). */
class ImportFile(val name: String?, val open: () -> InputStream?)

/** [done] of [total] workouts (files or backup rows) read and written; 0 of 0 while listing. */
data class ImportProgress(val done: Int, val total: Int)

sealed interface ImportEvent {
    /**
     * An import ended: [imported] new workouts, [pending] waiting for a decision, [duplicates]
     * already there, [failed] files unreadable; [changedRecordDistances] for files (a backup's
     * records come from the background pass).
     */
    data class Finished(
        val imported: Int,
        val pending: Int,
        val duplicates: Int,
        val failed: Int,
        val changedRecordDistances: Int?
    ) : ImportEvent

    /** A folder without GPX or TCX files. */
    data object NoFiles : ImportEvent

    /** The import stopped on an error; the batches before it are saved. */
    data class Failed(val message: String) : ImportEvent
}

/**
 * Runs workout imports in batches, outside the screen's view: a recreated screen finds the
 * import still running ([progress]) and gets its end ([events]). Each batch is saved in its own
 * transaction, so an interrupted import keeps what it wrote; run again, the saved workouts are
 * recognised as copies and skipped. Workouts that overlap others wait for the conflicts screen.
 */
class WorkoutImportViewModel(private val repository: WorkoutRepository) : ViewModel() {

    private val _progress = MutableStateFlow<ImportProgress?>(null)

    /** The running import; null when none runs. */
    val progress: StateFlow<ImportProgress?> = _progress.asStateFlow()

    private val _events = Channel<ImportEvent>(Channel.BUFFERED)

    /** The end of each import, once; kept until the screen is there to show it. */
    val events: Flow<ImportEvent> = _events.receiveAsFlow()

    /** How many imported workouts wait for a decision (the banner). */
    val pendingCount: Flow<Int> = repository.observePendingImportCount()

    private var job: Job? = null

    /** Imports the GPX / TCX [files] (listed off the main thread); ignored while an import runs. */
    fun importFiles(files: () -> List<ImportFile>) = start {
        val list = withContext(Dispatchers.IO) { files() }
        if (list.isEmpty()) return@start ImportEvent.NoFiles
        val totals = Totals()
        var failed = 0
        _progress.value = ImportProgress(0, list.size)
        list.chunked(FILE_BATCH).forEach { chunk ->
            val candidates = withContext(Dispatchers.IO) {
                chunk.mapNotNull { file ->
                    ensureActive()
                    readFile(file).also { if (it == null) failed++ }
                }
            }
            totals.add(repository.importBatch(candidates, fromBackup = false))
            _progress.value = ImportProgress(_progress.value!!.done + chunk.size, list.size)
        }
        ImportEvent.Finished(
            totals.ids.size, totals.pending, totals.duplicates, failed,
            changedRecordDistances = repository.recordDistancesHeldBy(totals.ids)
        )
    }

    /** Imports a JSON backup ([read] gives its text, off the main thread); ignored while an import runs. */
    fun importBackup(read: () -> String) = start {
        val workouts = withContext(Dispatchers.IO) {
            val json = read()
            WorkoutBackupFormat.parseBackupJson(json)
        }
        val totals = Totals()
        _progress.value = ImportProgress(0, workouts.size)
        workouts.chunked(BACKUP_BATCH).forEach { chunk ->
            totals.add(repository.importBatch(chunk.map { ImportCandidate(it) }, fromBackup = true))
            _progress.value = ImportProgress(_progress.value!!.done + chunk.size, workouts.size)
        }
        ImportEvent.Finished(totals.ids.size, totals.pending, totals.duplicates, failed = 0, changedRecordDistances = null)
    }

    private fun start(run: suspend () -> ImportEvent) {
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            _progress.value = ImportProgress(0, 0)
            val event = try {
                run()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Import failed: ${e.message}", e)
                ImportEvent.Failed(e.message ?: e.javaClass.simpleName)
            } finally {
                _progress.value = null
            }
            _events.send(event)
        }
    }

    /** One file as a candidate; null (logged) when it cannot be read. */
    private fun readFile(file: ImportFile): ImportCandidate? = try {
        val workout = file.open()?.use { TrackFileImport.parse(it, file.name) }
            ?: throw IllegalStateException("Cannot open ${file.name}")
        ImportCandidate(workout, source = file.name)
    } catch (e: Exception) {
        Log.w(TAG, "Skip track file ${file.name}: ${e.message}")
        null
    }

    private class Totals {
        val ids = mutableListOf<Long>()
        var pending = 0
        var duplicates = 0

        fun add(batch: ImportBatch) {
            ids += batch.insertedIds
            pending += batch.pending
            duplicates += batch.duplicates
        }
    }

    companion object {
        private const val TAG = "WorkoutImport"

        /** Files per transaction: a few dozen tracks in memory at once. */
        const val FILE_BATCH = 25

        /** Backup rows per transaction (saved uncomputed: small). */
        const val BACKUP_BATCH = 200
    }
}

class WorkoutImportViewModelFactory(private val repository: WorkoutRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(WorkoutImportViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return WorkoutImportViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
