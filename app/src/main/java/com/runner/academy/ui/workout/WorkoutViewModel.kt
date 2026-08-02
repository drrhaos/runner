package com.runner.academy.ui.workout

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.runner.academy.data.Workout
import com.runner.academy.data.WorkoutRepository
import com.runner.academy.data.WorkoutType
import com.runner.academy.util.SpeedPaceCalculator
import com.runner.academy.util.TrackDataJson
import com.runner.academy.util.WorkoutDataCleaner
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch

enum class WorkoutListFilter {
    ALL,
    FAVORITES
}

@OptIn(ExperimentalCoroutinesApi::class)
class WorkoutViewModel(private val repository: WorkoutRepository) : ViewModel() {

    val allWorkouts: Flow<List<Workout>> = repository.getAllWorkouts()

    private val _listFilter = MutableStateFlow(WorkoutListFilter.ALL)
    val listFilter: StateFlow<WorkoutListFilter> = _listFilter.asStateFlow()

    val pagedWorkouts: Flow<PagingData<Workout>> = _listFilter
        .flatMapLatest { filter ->
            Pager(
                config = PagingConfig(
                    pageSize = PAGE_SIZE,
                    prefetchDistance = PREFETCH_DISTANCE,
                    initialLoadSize = PAGE_SIZE,
                    enablePlaceholders = false
                ),
                pagingSourceFactory = {
                    when (filter) {
                        WorkoutListFilter.ALL -> repository.pagingSourceAll()
                        WorkoutListFilter.FAVORITES -> repository.pagingSourceFavorites()
                    }
                }
            ).flow
        }
        .cachedIn(viewModelScope)

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

    fun insertWorkout(workout: Workout) {
        viewModelScope.launch {
            try {
                repository.insertWorkout(workout)
                loadStatistics()
            } catch (e: Exception) {
                android.util.Log.e("WorkoutViewModel", "Error inserting workout: ${e.message}", e)
            }
        }
    }

    /**
     * Imports workouts (always with new auto-generated IDs). Returns inserted count.
     */
    suspend fun importWorkouts(workouts: List<Workout>): Int {
        if (workouts.isEmpty()) return 0
        val toInsert = workouts.map { it.copy(id = 0) }
        repository.insertWorkouts(toInsert)
        loadStatistics()
        return toInsert.size
    }

    fun updateWorkout(workout: Workout) {
        viewModelScope.launch {
            try {
                repository.updateWorkout(workout)
                loadStatistics()
            } catch (e: Exception) {
                android.util.Log.e("WorkoutViewModel", "Error updating workout: ${e.message}", e)
            }
        }
    }

    fun deleteWorkout(workout: Workout) {
        viewModelScope.launch {
            try {
                repository.deleteWorkout(workout)
                loadStatistics()
            } catch (e: Exception) {
                android.util.Log.e("WorkoutViewModel", "Error deleting workout: ${e.message}", e)
            }
        }
    }

    fun setListFilter(filter: WorkoutListFilter) {
        if (_listFilter.value == filter) return
        _listFilter.value = filter
        refreshListItemCount()
    }

    fun toggleFavorite(workout: Workout) {
        viewModelScope.launch {
            try {
                repository.setFavorite(workout.id, !workout.isFavorite)
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
                _totalDistance.value = totalDistanceKm
                _totalWorkouts.value = repository.getTotalWorkouts()
                _averageDuration.value = repository.getAverageDuration() ?: 0L
                _totalDuration.value = totalDurationMs
                _averagePace.value = SpeedPaceCalculator.overallAveragePace(
                    totalDistanceMeters = totalDistanceKm.toDouble() * 1000.0,
                    totalDurationSeconds = totalDurationMs / 1000.0
                )
                refreshListItemCount()
            } catch (e: Exception) {
                android.util.Log.e("WorkoutViewModel", "Error loading statistics: ${e.message}", e)
            }
        }
    }

    fun calculatePace(distance: Float, durationMs: Long): Float {
        if (distance <= 0 || durationMs <= 0) return 0f
        val durationMinutes = durationMs / 60000f
        return durationMinutes / distance
    }

    fun formatDuration(durationMs: Long): String {
        return com.runner.academy.util.FormatUtils.formatTime(durationMs)
    }

    fun formatPace(paceMinutes: Float): String {
        return com.runner.academy.util.FormatUtils.formatPace(paceMinutes)
    }

    fun getWorkoutTypes(): List<WorkoutType> {
        return WorkoutType.entries
    }

    /**
     * Очищает данные тренировки от GPS выбросов
     * @param forceClean если true, очистка выполняется принудительно, даже если needsCleaning возвращает false
     */
    suspend fun cleanWorkoutData(workout: Workout, forceClean: Boolean = false): Workout? {
        return try {
            if (workout.trackData == null) {
                android.util.Log.d("WorkoutViewModel", "Workout has no track data to clean")
                return workout
            }

            val trackData = TrackDataJson.parse(workout.trackData)
                ?: run {
                    android.util.Log.d("WorkoutViewModel", "Workout track data could not be parsed")
                    return workout
                }

            // Проверяем, нужна ли очистка (если не принудительная)
            if (!forceClean) {
                val needsCleaning = WorkoutDataCleaner.needsCleaning(trackData)
                if (!needsCleaning) {
                    android.util.Log.d("WorkoutViewModel", "Workout data doesn't need cleaning")
                    return workout
                }
            }

            android.util.Log.d(
                "WorkoutViewModel",
                "Cleaning workout data for workout ${workout.id} (forceClean=$forceClean)"
            )

            // Очищаем данные
            val cleanedTrackData = WorkoutDataCleaner.cleanTrackData(trackData)
            if (
                cleanedTrackData.points.size == trackData.points.size &&
                cleanedTrackData.totalDistance == trackData.totalDistance &&
                cleanedTrackData.totalDuration == trackData.totalDuration
            ) {
                android.util.Log.d("WorkoutViewModel", "Cleaning produced no meaningful changes")
                return workout
            }

            // Создаем обновленную тренировку с очищенными данными
            val cleanedWorkout = workout.copy(
                distance = cleanedTrackData.totalDistance / 1000f, // Конвертируем в км
                duration = cleanedTrackData.totalDuration,
                avgPace = calculatePace(
                    cleanedTrackData.totalDistance / 1000f,
                    cleanedTrackData.totalDuration
                ),
                trackData = TrackDataJson.toJson(cleanedTrackData)
            )

            // Сохраняем очищенные данные в базу
            repository.updateWorkout(cleanedWorkout)

            android.util.Log.d("WorkoutViewModel", "Workout data cleaned and saved successfully")
            cleanedWorkout
        } catch (e: Exception) {
            android.util.Log.e("WorkoutViewModel", "Error cleaning workout data: ${e.message}", e)
            null
        }
    }

    companion object {
        const val PAGE_SIZE = 20
        const val PREFETCH_DISTANCE = 5
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
