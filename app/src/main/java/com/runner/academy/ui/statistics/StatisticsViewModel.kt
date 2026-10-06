package com.runner.academy.ui.statistics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.runner.academy.data.WorkoutRepository
import com.runner.academy.data.WorkoutType
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.*

data class StatisticsData(
    val totalWorkouts: Int = 0,
    val totalDistance: Float = 0f,
    val totalDuration: Long = 0L,
    /** Σ movingDuration: time without auto-pauses. */
    val totalMovingDuration: Long = 0L,
    val averagePace: Float = 0f,
    val averageDistance: Float = 0f,
    val averageDuration: Long = 0L,
    /** Steps/min weighted by moving time over the workouts with cadence; null: none has it. */
    val averageCadence: Float? = null,
    val totalCalories: Int = 0,
    val bestPace: Float = 0f,
    val longestDistance: Float = 0f,
    val longestDuration: Long = 0L,
    val workoutsThisWeek: Int = 0,
    val workoutsThisMonth: Int = 0,
    val workoutsByType: Map<WorkoutType, Int> = emptyMap(),
    val distanceByType: Map<WorkoutType, Float> = emptyMap(),
    val weeklyData: List<WeeklyData> = emptyList(),
    val monthlyData: List<MonthlyData> = emptyList()
)

data class WeeklyData(
    val weekStart: Date,
    val weekEnd: Date,
    val workouts: Int,
    val distance: Float,
    val duration: Long
)

data class MonthlyData(
    val month: Int,
    val year: Int,
    val workouts: Int,
    val distance: Float,
    val duration: Long
)

class StatisticsViewModel(private val repository: WorkoutRepository) : ViewModel() {

    private val _statisticsData = MutableStateFlow(StatisticsData())
    val statisticsData: StateFlow<StatisticsData> = _statisticsData.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        loadStatistics()
    }

    private fun loadStatistics() {
        viewModelScope.launch {
            try {
                _isLoading.value = true

                // Только скалярные поля: сотни тренировок не должны тянуть в память свои треки
                val allWorkouts = repository.getStatsRows()
                
                _statisticsData.value = StatisticsSummary.of(allWorkouts, Date())
            } catch (e: Exception) {
                com.runner.academy.util.ErrorHandler.handleLoadError(
                    com.runner.academy.RunnerApplication.instance,
                    e,
                    false
                )
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun refreshStatistics() {
        loadStatistics()
    }
}

class StatisticsViewModelFactory(private val repository: WorkoutRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(StatisticsViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return StatisticsViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
