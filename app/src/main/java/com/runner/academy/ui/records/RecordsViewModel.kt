package com.runner.academy.ui.records

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.runner.academy.data.BackfillState
import com.runner.academy.data.RecordDistance
import com.runner.academy.data.WorkoutRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.ExperimentalCoroutinesApi

/** The records screen: the record book, its recalculation and the empty state. */
@OptIn(ExperimentalCoroutinesApi::class)
class RecordsViewModel(
    private val repository: WorkoutRepository,
    backfillProgress: Flow<BackfillState>
) : ViewModel() {

    /** Null until the record book is read the first time. */
    val state: StateFlow<RecordsScreenState?> =
        combine(repository.observeRecordBook(), repository.observeRecordsPending(), backfillProgress) { book, pending, progress ->
            RecordsScreen.state(book, pending, progress, manualOnly = false)
        }
            .distinctUntilChanged()
            .mapLatest { state ->
                // Asked only for the empty state: workouts saved, none with a track
                if (state.empty && hasOnlyManualWorkouts()) state.copy(manualOnly = true) else state
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    private val _expanded = MutableStateFlow<Set<RecordDistance>>(emptySet())

    /** Distances whose history is unfolded; kept across a rotation. */
    val expanded: StateFlow<Set<RecordDistance>> = _expanded.asStateFlow()

    fun toggleHistory(distance: RecordDistance) {
        _expanded.update { if (distance in it) it - distance else it + distance }
    }

    private suspend fun hasOnlyManualWorkouts(): Boolean =
        repository.getTotalWorkouts() > 0 && repository.countRoutes(excludeId = NO_WORKOUT) == 0

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        const val NO_WORKOUT = -1L
    }
}

class RecordsViewModelFactory(
    private val repository: WorkoutRepository,
    private val backfillProgress: Flow<BackfillState>
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(RecordsViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return RecordsViewModel(repository, backfillProgress) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
