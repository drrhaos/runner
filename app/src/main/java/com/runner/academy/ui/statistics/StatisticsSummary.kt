package com.runner.academy.ui.statistics

import com.runner.academy.data.WorkoutStatsRow
import com.runner.academy.util.PaceMath
import java.util.Calendar
import java.util.Date

/** The statistics screen's numbers from the scalar rows (pure, so it is tested without a database). */
object StatisticsSummary {

    fun of(rows: List<WorkoutStatsRow>, now: Date): StatisticsData {
        if (rows.isEmpty()) return StatisticsData()

        // Вычисляем базовую статистику
        val totalWorkouts = rows.size
        val totalDistance = rows.sumOf { it.distance.toDouble() }.toFloat()
        val totalDuration = rows.sumOf { it.duration }
        val totalMovingDuration = rows.sumOf { it.movingDuration }
        val totalCalories = rows.sumOf { it.calories ?: 0 }

        val averageDistance = if (totalWorkouts > 0) totalDistance / totalWorkouts else 0f
        // Average time and pace are over moving time, like each workout's avgPace
        val averageDuration = if (totalWorkouts > 0) totalMovingDuration / totalWorkouts else 0L
        val averagePace = PaceMath.avgPace(totalDistance, totalMovingDuration)
        val averageCadence = averageCadence(rows)

        // Находим лучшие результаты
        val bestPace = rows
            .asSequence()
            .map { it.avgPace }
            .filter { it > 0f }
            .minOrNull()
            ?: 0f
        val longestDistance = rows.maxOfOrNull { it.distance } ?: 0f
        // The longest workout is by total time
        val longestDuration = rows.maxOfOrNull { it.duration } ?: 0L

        // Статистика по типам тренировок
        val workoutsByType = rows.groupingBy { it.type }.eachCount()
        val distanceByType = rows.groupBy { it.type }
            .mapValues { (_, workouts) -> workouts.sumOf { it.distance.toDouble() }.toFloat() }

        // Статистика за неделю и месяц
        val weekAgo = Date(now.time - 7 * 24 * 60 * 60 * 1000L)
        val monthAgo = Date(now.time - 30 * 24 * 60 * 60 * 1000L)

        val workoutsThisWeek = rows.count { it.date >= weekAgo }
        val workoutsThisMonth = rows.count { it.date >= monthAgo }

        // Недельные данные (последние 12 недель)
        val weeklyData = generateWeeklyData(rows, now)

        // Месячные данные (последние 12 месяцев)
        val monthlyData = generateMonthlyData(rows, now)

        return StatisticsData(
            totalWorkouts = totalWorkouts,
            totalDistance = totalDistance,
            totalDuration = totalDuration,
            totalMovingDuration = totalMovingDuration,
            averagePace = averagePace,
            averageDistance = averageDistance,
            averageDuration = averageDuration,
            averageCadence = averageCadence,
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
    }

    /**
     * Only over workouts with cadence (the rest are no data, not zeros), each weighted by its
     * moving time. Approximate: a workout's cadence is over its moving time *with steps* (the
     * stretches without steps are left out of it), which the stats rows do not carry, so a
     * workout that lost its steps half-way weighs more than its share of steps.
     */
    private fun averageCadence(rows: List<WorkoutStatsRow>): Float? {
        val withCadence = rows.filter { it.avgCadence?.isFinite() == true && it.movingDuration > 0L }
        val totalMs = withCadence.sumOf { it.movingDuration }
        if (totalMs <= 0L) return null
        val weighted = withCadence.sumOf { it.avgCadence!!.toDouble() * it.movingDuration }
        return (weighted / totalMs).toFloat()
    }

    private fun generateWeeklyData(workouts: List<WorkoutStatsRow>, now: Date): List<WeeklyData> {
        val calendar = Calendar.getInstance()
        val weeklyData = mutableListOf<WeeklyData>()
        
        // Генерируем данные за последние 12 недель
        repeat(12) { weekOffset ->
            calendar.time = now
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
                    duration = weekWorkouts.sumOf { it.duration }
                )
            )
        }
        
        return weeklyData.reversed() // От старых к новым
    }

    private fun generateMonthlyData(workouts: List<WorkoutStatsRow>, now: Date): List<MonthlyData> {
        val calendar = Calendar.getInstance()
        val monthlyData = mutableListOf<MonthlyData>()
        
        // Генерируем данные за последние 12 месяцев
        repeat(12) { monthOffset ->
            calendar.time = now
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
                    duration = monthWorkouts.sumOf { it.duration }
                )
            )
        }
        
        return monthlyData.reversed() // От старых к новым
    }
}
