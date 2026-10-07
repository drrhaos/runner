package com.runner.academy.ui.workout

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.runner.academy.data.GpxImport
import com.runner.academy.data.Workout
import com.runner.academy.data.WorkoutListItem
import com.runner.academy.data.WorkoutRepository
import com.runner.academy.data.WorkoutType
import com.runner.academy.util.PaceMath
import com.runner.academy.util.WorkoutTrackRebuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class WorkoutListFilter {
    ALL,
    FAVORITES
}

@OptIn(ExperimentalCoroutinesApi::class)
class WorkoutViewModel(private val repository: WorkoutRepository) : ViewModel() {

    /**
     * Every workout with its track — only for explicit "export all" actions. Screens page
     * through [pagedWorkouts] / [routePages] instead.
     */
    suspend fun loadAllForExport(): List<Workout> = repository.getAllWorkouts().first()

    private val _listFilter = MutableStateFlow(WorkoutListFilter.ALL)
    val listFilter: StateFlow<WorkoutListFilter> = _listFilter.asStateFlow()

    val pagedWorkouts: Flow<PagingData<WorkoutListItem>> = _listFilter
        .flatMapLatest { filter ->
            Pager(
                config = PAGING_CONFIG,
                pagingSourceFactory = {
                    when (filter) {
                        WorkoutListFilter.ALL -> repository.pagingSourceAll()
                        WorkoutListFilter.FAVORITES -> repository.pagingSourceFavorites()
                    }
                }
            ).flow
        }
        .cachedIn(viewModelScope)

    suspend fun countRoutes(excludeId: Long): Int = repository.countRoutes(excludeId)

    /** Route picker pages (workouts with a track, favorites first), same bounded window. */
    fun routePages(excludeId: Long): Flow<PagingData<WorkoutListItem>> =
        Pager(PAGING_CONFIG) { repository.pagingSourceRoutes(excludeId) }.flow

    /** The full track of a picked route; null when it has none (or was deleted meanwhile). */
    suspend fun getTrackData(id: Long): String? = repository.getTrackData(id)

    private val _totalDistance = MutableStateFlow(0f)
    val totalDistance: StateFlow<Float> = _totalDistance.asStateFlow()

    private val _totalWorkouts = MutableStateFlow(0)
    val totalWorkouts: StateFlow<Int> = _totalWorkouts.asStateFlow()

    private val _averageDuration = MutableStateFlow(0L)
    val averageDuration: StateFlow<Long> = _averageDuration.asStateFlow()

    private val _totalDuration = MutableStateFlow(0L)
    val totalDuration: StateFlow<Long> = _totalDuration.asStateFlow()

    private val _averagePace = MutableStateFlow(0f)
    val averagePace: StateFlow<Float> = _averagePace.asStateFlow()

    private val _listItemCount = MutableStateFlow(0)
    val listItemCount: StateFlow<Int> = _listItemCount.asStateFlow()

    init {
        loadStatistics()
    }

    fun refreshStatistics() {
        loadStatistics()
    }

    /** Imports a backup as new workouts (metrics follow in background). Returns inserted count. */
    suspend fun importBackup(workouts: List<Workout>): Int =
        import(workouts, nothing = 0) { repository.importBackup(it).size }

    /**
     * Imports workouts read from GPX files as new workouts. Returns the new ids and the record
     * distances they hold (for "Records updated: N").
     */
    suspend fun importGpx(workouts: List<Workout>): GpxImport =
        import(workouts, nothing = GpxImport(emptyList(), changedRecordDistances = 0), insert = repository::importGpx)

    /** [insert]s [workouts] and refreshes the statistics; [nothing] for an empty list. */
    private suspend fun <R> import(workouts: List<Workout>, nothing: R, insert: suspend (List<Workout>) -> R): R {
        if (workouts.isEmpty()) return nothing
        val result = insert(workouts)
        loadStatistics()
        return result
    }

    /**
     * Сохраняет тренировку из формы добавления/редактирования, пересобирая время трека
     * (см. [WorkoutTrackRebuilder]). Suspend, чтобы экран закрывался только после записи в БД.
     */
    suspend fun saveFromForm(workout: Workout, original: Workout?): WorkoutTrackRebuilder.TimeSource {
        val rebuilt = withContext(Dispatchers.Default) {
            WorkoutTrackRebuilder.rebuild(
                originalTrackJson = original?.trackData,
                selectedTrackJson = workout.trackData,
                originalDate = original?.date,
                newDate = workout.date,
                durationMs = workout.duration
            )
        }
        val toSave = workout.copy(trackData = rebuilt.trackDataJson)
        if (original != null) {
            repository.saveEdited(toSave)
        } else {
            repository.insertWorkout(toSave)
        }
        loadStatistics()
        return rebuilt.timeSource
    }

    /** Suspend so the caller leaves the screen only after the row is actually deleted. */
    suspend fun hasGpsDiagnostics(workoutId: Long): Boolean = repository.hasGpsDiagnostics(workoutId)

    suspend fun copyGpsDiagnosticsForSharing(workoutId: Long): java.io.File? =
        repository.copyGpsDiagnosticsForSharing(workoutId)

    suspend fun deleteWorkout(workout: Workout) {
        repository.deleteWorkout(workout)
        loadStatistics()
    }

    fun setListFilter(filter: WorkoutListFilter) {
        if (_listFilter.value == filter) return
        _listFilter.value = filter
        refreshListItemCount()
    }

    /** Flips the flag of workout [id], which is [isFavorite] now (a list row or the details). */
    fun toggleFavorite(id: Long, isFavorite: Boolean) {
        viewModelScope.launch {
            try {
                repository.setFavorite(id, !isFavorite)
                loadStatistics()
            } catch (e: Exception) {
                android.util.Log.e("WorkoutViewModel", "Error toggling favorite: ${e.message}", e)
            }
        }
    }

    fun getWorkoutById(id: Long): Flow<Workout?> {
        return repository.getWorkoutById(id)
    }

    fun refreshListItemCount() {
        viewModelScope.launch {
            try {
                _listItemCount.value = when (_listFilter.value) {
                    WorkoutListFilter.ALL -> repository.getTotalWorkouts()
                    WorkoutListFilter.FAVORITES -> repository.getFavoriteWorkoutsCount()
                }
            } catch (e: Exception) {
                android.util.Log.e("WorkoutViewModel", "Error loading list count: ${e.message}", e)
            }
        }
    }

    private fun loadStatistics() {
        viewModelScope.launch {
            try {
                val totalDistanceKm = repository.getTotalDistance() ?: 0f
                val totalDurationMs = repository.getTotalDuration() ?: 0L
                val totalMovingMs = repository.getTotalMovingDuration() ?: 0L
                _totalDistance.value = totalDistanceKm
                _totalWorkouts.value = repository.getTotalWorkouts()
                // Average time and pace are over moving time, like each workout's avgPace
                _averageDuration.value = repository.getAverageMovingDuration() ?: 0L
                _totalDuration.value = totalDurationMs
                _averagePace.value = PaceMath.avgPace(totalDistanceKm, totalMovingMs)
                refreshListItemCount()
            } catch (e: Exception) {
                android.util.Log.e("WorkoutViewModel", "Error loading statistics: ${e.message}", e)
            }
        }
    }

    fun formatDuration(durationMs: Long): String {
        return com.runner.academy.util.FormatUtils.formatTime(durationMs)
    }

    fun formatPace(paceMinutes: Float, context: Context? = null): String {
        return com.runner.academy.util.FormatUtils.formatPace(paceMinutes, context)
    }

    fun getWorkoutTypes(): List<WorkoutType> {
        return WorkoutType.entries
    }

    companion object {
        private const val PAGE_SIZE = 20
        private const val PREFETCH_DISTANCE = 5

        /**
         * Pages hold list rows without tracks ([WorkoutListItem]); still only a window of
         * [MAX_LOADED_ITEMS] stays in memory, pages scrolled past are dropped and reloaded on
         * the way back. A year of running is hundreds of workouts.
         */
        private const val MAX_LOADED_ITEMS = 60

        val PAGING_CONFIG = PagingConfig(
            pageSize = PAGE_SIZE,
            prefetchDistance = PREFETCH_DISTANCE,
            initialLoadSize = PAGE_SIZE,
            enablePlaceholders = false,
            maxSize = MAX_LOADED_ITEMS
        )
    }
}

class WorkoutViewModelFactory(private val repository: WorkoutRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(WorkoutViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return WorkoutViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
