package com.runner.academy.ui.workout

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.runner.academy.data.ConflictResolution
import com.runner.academy.data.PendingConflict
import com.runner.academy.data.WorkoutRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ImportConflictsViewModel(private val repository: WorkoutRepository) : ViewModel() {

    val conflicts: Flow<List<PendingConflict>> = repository.observePendingConflicts()

    private val _busy = MutableStateFlow(false)

    /** A decision is being saved: the buttons wait (a second tap would decide twice). */
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    fun resolve(pendingId: Long, resolution: ConflictResolution) = decide {
        repository.resolvePendingImport(pendingId, resolution)
    }

    fun resolveAll(resolution: ConflictResolution) = decide {
        repository.resolveAllPendingImports(resolution)
    }

    private fun decide(block: suspend () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                block()
            } finally {
                _busy.value = false
            }
        }
    }
}

class ImportConflictsViewModelFactory(private val repository: WorkoutRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ImportConflictsViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return ImportConflictsViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
