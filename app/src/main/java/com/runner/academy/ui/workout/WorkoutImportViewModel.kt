package com.runner.academy.ui.workout

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.runner.academy.data.WorkoutRepository
import com.runner.academy.service.BackgroundImport
import com.runner.academy.service.ImportRequest
import com.runner.academy.service.ImportState
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * The screen's side of the import, which runs in WorkManager ([BackgroundImport]): it goes on
 * when the screen is left or the process killed. The screen shows its [running] state and, once,
 * its end ([ended], then [markShown]).
 */
class WorkoutImportViewModel(
    private val appContext: Context,
    repository: WorkoutRepository
) : ViewModel() {

    private val state: Flow<ImportState?> = BackgroundImport.observe(appContext)

    /** The running import; null when none runs. */
    val running: Flow<ImportState.Running?> = state.map { it as? ImportState.Running }.distinctUntilChanged()

    /**
     * The end of the latest import while it was not shown: collected while the screen is there,
     * an import that ended meanwhile is reported when the user is back.
     */
    val ended: Flow<ImportState.Ended> = state
        .filterIsInstance<ImportState.Ended>()
        .distinctUntilChanged()
        .filter { !BackgroundImport.wasShown(appContext, it.id) }

    private val _rejected = Channel<Unit>(Channel.CONFLATED)

    /** A start while an import runs: nothing was started. */
    val rejected: Flow<Unit> = _rejected.receiveAsFlow()

    /** How many imported workouts wait for a decision (the banner). */
    val pendingCount: Flow<Int> = repository.observePendingImportCount()

    fun importFiles(uris: List<Uri>) = start(ImportRequest(ImportRequest.Kind.FILES, uris.map(Uri::toString)))

    fun importFolder(treeUri: Uri) = start(ImportRequest(ImportRequest.Kind.FOLDER, listOf(treeUri.toString())))

    fun importBackup(uri: Uri) = start(ImportRequest(ImportRequest.Kind.BACKUP, listOf(uri.toString())))

    /** The screen showed [ended]: not shown again. */
    fun markShown(ended: ImportState.Ended) = BackgroundImport.markShown(appContext, ended.id)

    /** Stops the running import; what it saved stays. */
    fun stop() = BackgroundImport.stop(appContext)

    private fun start(request: ImportRequest) {
        viewModelScope.launch {
            if (!BackgroundImport.start(appContext, request)) _rejected.send(Unit)
        }
    }
}

class WorkoutImportViewModelFactory(
    private val context: Context,
    private val repository: WorkoutRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(WorkoutImportViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return WorkoutImportViewModel(context.applicationContext, repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
