package com.runner.academy.ui.statistics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.runner.academy.data.Workout
import com.runner.academy.data.WorkoutRepository
import com.runner.academy.data.WorkoutType
import com.runner.academy.util.SpeedPaceCalculator
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.*

data class StatisticsData(
    val totalWorkouts: Int = 0,
    val totalDistance: Float = 0f,
    val totalDuration: Long = 0L,
    val averagePace: Float = 0f,
    val averageDistance: Float = 0f,
    val averageDuration: Long = 0L,
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

                // Загружаем все тренировки
                val allWorkouts = repository.getAllWorkouts().first()
                
                if (allWorkouts.isEmpty()) {
                    _statisticsData.value = StatisticsData()
                    return@launch
                }

                // Вычисляем базовую статистику
                val totalWorkouts = allWorkouts.size
                val totalDistance = allWorkouts.sumOf { it.distance.toDouble() }.toFloat()
                val totalDuration = allWorkouts.sumOf { it.duration.toLong() }
                val totalCalories = allWorkouts.sumOf { it.calories ?: 0 }
                
                val averageDistance = if (totalWorkouts > 0) totalDistance / totalWorkouts else 0f
                val averageDuration = if (totalWorkouts > 0) totalDuration / totalWorkouts else 0L
                val averagePace = SpeedPaceCalculator.overallAveragePace(
                    totalDistanceMeters = totalDistance * 1000.0,
                    totalDurationSeconds = totalDuration / 1000.0
                )

                // Находим лучшие результаты
                val bestPace = allWorkouts.minOfOrNull { it.avgPace } ?: 0f
                val longestDistance = allWorkouts.maxOfOrNull { it.distance } ?: 0f
                val longestDuration = allWorkouts.maxOfOrNull { it.duration } ?: 0L

                // Статистика по типам тренировок
                val workoutsByType = allWorkouts.groupingBy { it.type }.eachCount()
                val distanceByType = allWorkouts.groupBy { it.type }
                    .mapValues { (_, workouts) -> workouts.sumOf { it.distance.toDouble() }.toFloat() }

                // Статистика за неделю и месяц
                val now = Date()
                val weekAgo = Date(now.time - 7 * 24 * 60 * 60 * 1000L)
                val monthAgo = Date(now.time - 30 * 24 * 60 * 60 * 1000L)
                
                val workoutsThisWeek = allWorkouts.count { it.date >= weekAgo }
                val workoutsThisMonth = allWorkouts.count { it.date >= monthAgo }

                // Недельные данные (последние 12 недель)
                val weeklyData = generateWeeklyData(allWorkouts)
                
                // Месячные данные (последние 12 месяцев)
                val monthlyData = generateMonthlyData(allWorkouts)

                _statisticsData.value = StatisticsData(
                    totalWorkouts = totalWorkouts,
                    totalDistance = totalDistance,
                    totalDuration = totalDuration,
                    averagePace = averagePace,
                    averageDistance = averageDistance,
                    averageDuration = averageDuration,
                    totalCalories = totalCalories,
                    bestPace = bestPace,
                    longestDistance = longestDistance,
                    longestDuration = longestDuration,
                    workoutsThisWeek = workoutsThisWeek,
                    workoutsThisMonth = workoutsThisMonth,
                    workoutsByType = workoutsByType,
                    distanceByType = distanceByType,
                    weeklyData = weeklyData,
                    monthlyData = monthlyData
                )
                
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

    private fun generateWeeklyData(workouts: List<Workout>): List<WeeklyData> {
        val calendar = Calendar.getInstance()
        val weeklyData = mutableListOf<WeeklyData>()
        
        // Генерируем данные за последние 12 недель
        repeat(12) { weekOffset ->
            calendar.time = Date()
            calendar.add(Calendar.WEEK_OF_YEAR, -weekOffset)
            calendar.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
            val weekStart = calendar.time
            
            calendar.add(Calendar.DAY_OF_YEAR, 6)
            val weekEnd = calendar.time
            
            val weekWorkouts = workouts.filter { workout ->
                workout.date >= weekStart && workout.date <= weekEnd
            }
            
            weeklyData.add(
                WeeklyData(
                    weekStart = weekStart,
                    weekEnd = weekEnd,
                    workouts = weekWorkouts.size,
                    distance = weekWorkouts.sumOf { it.distance.toDouble() }.toFloat(),
                    duration = weekWorkouts.sumOf { it.duration.toLong() }
                )
            )
        }
        
        return weeklyData.reversed() // От старых к новым
    }

    private fun generateMonthlyData(workouts: List<Workout>): List<MonthlyData> {
        val calendar = Calendar.getInstance()
        val monthlyData = mutableListOf<MonthlyData>()
        
        // Генерируем данные за последние 12 месяцев
        repeat(12) { monthOffset ->
            calendar.time = Date()
            calendar.add(Calendar.MONTH, -monthOffset)
            val month = calendar.get(Calendar.MONTH) + 1
            val year = calendar.get(Calendar.YEAR)
            
            val monthWorkouts = workouts.filter { workout ->
                val workoutCalendar = Calendar.getInstance()
                workoutCalendar.time = workout.date
                workoutCalendar.get(Calendar.MONTH) + 1 == month &&
                workoutCalendar.get(Calendar.YEAR) == year
            }
            
            monthlyData.add(
                MonthlyData(
                    month = month,
                    year = year,
                    workouts = monthWorkouts.size,
                    distance = monthWorkouts.sumOf { it.distance.toDouble() }.toFloat(),
                    duration = monthWorkouts.sumOf { it.duration.toLong() }
                )
            )
        }
        
        return monthlyData.reversed() // От старых к новым
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
