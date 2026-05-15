package com.example.runner.ui.workout

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.runner.data.Workout
import com.example.runner.data.WorkoutDao
import com.example.runner.data.WorkoutDatabase
import com.example.runner.data.WorkoutType
import com.example.runner.util.WorkoutDataCleaner
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Date

class WorkoutViewModel(private val workoutDao: WorkoutDao) : ViewModel() {

    val allWorkouts: Flow<List<Workout>> = workoutDao.getAllWorkouts()

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
                workoutDao.insertWorkout(workout)
                loadStatistics()
            } catch (e: Exception) {
                // Handle error
            }
        }
    }

    fun updateWorkout(workout: Workout) {
        viewModelScope.launch {
            try {
                workoutDao.updateWorkout(workout)
                loadStatistics()
            } catch (e: Exception) {
                // Handle error
            }
        }
    }

    fun deleteWorkout(workout: Workout) {
        viewModelScope.launch {
            try {
                workoutDao.deleteWorkout(workout)
                loadStatistics()
            } catch (e: Exception) {
                // Handle error
            }
        }
    }

    fun getWorkoutById(id: Long): Flow<Workout?> {
        return workoutDao.getWorkoutById(id)
    }

    private fun loadStatistics() {
        viewModelScope.launch {
            try {
                _totalDistance.value = workoutDao.getTotalDistance() ?: 0f
                _totalWorkouts.value = workoutDao.getTotalWorkouts()
                _averageDuration.value = workoutDao.getAverageDuration() ?: 0L
            } catch (e: Exception) {
                // Handle error
            }
        }
    }

    fun calculatePace(distance: Float, durationMs: Long): Float {
        if (distance <= 0 || durationMs <= 0) return 0f
        val durationMinutes = durationMs / 60000f
        return durationMinutes / distance
    }

    fun formatDuration(durationMs: Long): String {
        return com.example.runner.util.FormatUtils.formatTime(durationMs)
    }

    fun formatPace(paceMinutes: Float): String {
        return com.example.runner.util.FormatUtils.formatPace(paceMinutes)
    }

    fun getWorkoutTypeDisplayName(type: WorkoutType, context: Context): String {
        return when (type) {
            WorkoutType.EASY_RUN -> context.getString(R.string.workout_type_easy_run)
            WorkoutType.TEMPO_RUN -> context.getString(R.string.workout_type_tempo_run)
            WorkoutType.INTERVAL_TRAINING -> context.getString(R.string.workout_type_interval_training)
            WorkoutType.LONG_RUN -> context.getString(R.string.workout_type_long_run)
            WorkoutType.RECOVERY_RUN -> context.getString(R.string.workout_type_recovery_run)
            WorkoutType.RACE -> context.getString(R.string.workout_type_competition)
        }
    }

    fun getWorkoutTypes(): List<WorkoutType> {
        return WorkoutType.values().toList()
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
            val trackData = gson.fromJson(workout.trackData, com.example.runner.data.TrackData::class.java)
            
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
            workoutDao.updateWorkout(cleanedWorkout)
            
            android.util.Log.d("WorkoutViewModel", "Workout data cleaned and saved successfully")
            cleanedWorkout
        } catch (e: Exception) {
            android.util.Log.e("WorkoutViewModel", "Error cleaning workout data: ${e.message}", e)
            null
        }
    }
}

class WorkoutViewModelFactory(private val workoutDao: WorkoutDao) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(WorkoutViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return WorkoutViewModel(workoutDao) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
