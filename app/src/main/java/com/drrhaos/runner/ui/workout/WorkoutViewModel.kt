package com.drrhaos.runner.ui.workout

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.drrhaos.runner.R
import com.drrhaos.runner.data.Workout
import com.drrhaos.runner.data.WorkoutRepository
import com.drrhaos.runner.data.WorkoutType
import com.drrhaos.runner.util.WorkoutDataCleaner
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class WorkoutViewModel(private val repository: WorkoutRepository) : ViewModel() {

    val allWorkouts: Flow<List<Workout>> = repository.getAllWorkouts()

    private val _totalDistance = MutableStateFlow(0f)
    val totalDistance: StateFlow<Float> = _totalDistance.asStateFlow()

    private val _totalWorkouts = MutableStateFlow(0)
    val totalWorkouts: StateFlow<Int> = _totalWorkouts.asStateFlow()

    private val _averageDuration = MutableStateFlow(0L)
    val averageDuration: StateFlow<Long> = _averageDuration.asStateFlow()

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

    fun getWorkoutById(id: Long): Flow<Workout?> {
        return repository.getWorkoutById(id)
    }

    private fun loadStatistics() {
        viewModelScope.launch {
            try {
                _totalDistance.value = repository.getTotalDistance() ?: 0f
                _totalWorkouts.value = repository.getTotalWorkouts()
                _averageDuration.value = repository.getAverageDuration() ?: 0L
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
        return com.drrhaos.runner.util.FormatUtils.formatTime(durationMs)
    }

    fun formatPace(paceMinutes: Float): String {
        return com.drrhaos.runner.util.FormatUtils.formatPace(paceMinutes)
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
            
            val gson = com.google.gson.Gson()
            val trackData = gson.fromJson(workout.trackData, com.drrhaos.runner.data.TrackData::class.java)
            
            // Проверяем, нужна ли очистка (если не принудительная)
            if (!forceClean) {
                val needsCleaning = WorkoutDataCleaner.needsCleaning(trackData)
                if (!needsCleaning) {
                    android.util.Log.d("WorkoutViewModel", "Workout data doesn't need cleaning")
                    return workout
                }
            }
            
            android.util.Log.d("WorkoutViewModel", "Cleaning workout data for workout ${workout.id} (forceClean=$forceClean)")
            
            // Очищаем данные
            val cleanedTrackData = WorkoutDataCleaner.cleanTrackData(trackData)
            
            // Создаем обновленную тренировку с очищенными данными
            val cleanedWorkout = workout.copy(
                distance = cleanedTrackData.totalDistance / 1000f, // Конвертируем в км
                duration = cleanedTrackData.totalDuration,
                avgPace = calculatePace(cleanedTrackData.totalDistance / 1000f, cleanedTrackData.totalDuration),
                trackData = gson.toJson(cleanedTrackData)
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
