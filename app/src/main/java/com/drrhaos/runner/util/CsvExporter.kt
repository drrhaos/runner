package com.drrhaos.runner.util

import android.content.Context
import com.drrhaos.runner.R
import com.drrhaos.runner.data.Workout
import com.drrhaos.runner.data.WorkoutType
import com.drrhaos.runner.data.displayName
import java.text.SimpleDateFormat
import java.util.*

/**
 * Утилита для экспорта статистики тренировок в CSV формат
 */
object CsvExporter {
    
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    
    /**
     * Экспортирует список тренировок в CSV формат
     */
    fun exportWorkoutsToCsv(workouts: List<Workout>, context: Context): String {
        val builder = StringBuilder()
        
        builder.append(context.getString(R.string.csv_header))
        
        // Данные тренировок
        workouts.forEach { workout ->
            val date = dateFormat.format(workout.date)
            val type = workout.type.displayName(context)
            val distance = String.format(Locale.US, "%.2f", workout.distance)
            val durationMinutes = workout.duration / 60000.0
            val duration = String.format(Locale.US, "%.2f", durationMinutes)
            val pace = if (workout.avgPace > 0) {
                FormatUtils.formatPace(workout.avgPace, context)
            } else {
                "--:--"
            }
            val calories = workout.calories?.toString() ?: ""
            val notes = escapeCsv(workout.notes ?: "")
            
            builder.append("$date,$type,$distance,$duration,$pace,$calories,$notes\n")
        }
        
        return builder.toString()
    }
    
    /**
     * Экспортирует статистику в CSV формат
     */
    fun exportStatisticsToCsv(
        totalWorkouts: Int,
        totalDistance: Float,
        totalDuration: Long,
        totalCalories: Int,
        averagePace: Float,
        averageDistance: Float,
        averageDuration: Long,
        bestPace: Float,
        longestDistance: Float,
        longestDuration: Long,
        workoutsByType: Map<WorkoutType, Int>,
        distanceByType: Map<WorkoutType, Float>,
        context: Context
    ): String {
        val builder = StringBuilder()
        
        builder.append(context.getString(R.string.csv_statistics_header))
        builder.append("${context.getString(R.string.csv_total_workouts)},$totalWorkouts\n")
        builder.append("${context.getString(R.string.csv_total_distance)},${String.format(Locale.US, "%.2f", totalDistance)}\n")
        builder.append("${context.getString(R.string.csv_total_time)},${String.format(Locale.US, "%.2f", totalDuration / 3600000.0)}\n")
        builder.append("${context.getString(R.string.csv_total_calories)},$totalCalories\n")
        builder.append("${context.getString(R.string.csv_average_pace)},${if (averagePace > 0) FormatUtils.formatPace(averagePace, context) else "--:--"}\n")
        builder.append("${context.getString(R.string.csv_average_distance)},${String.format(Locale.US, "%.2f", averageDistance)}\n")
        builder.append("${context.getString(R.string.csv_average_time)},${String.format(Locale.US, "%.2f", averageDuration / 60000.0)}\n")
        builder.append("${context.getString(R.string.csv_best_pace)},${if (bestPace > 0) FormatUtils.formatPace(bestPace, context) else "--:--"}\n")
        builder.append("${context.getString(R.string.csv_max_distance)},${String.format(Locale.US, "%.2f", longestDistance)}\n")
        builder.append("${context.getString(R.string.csv_longest_time)},${String.format(Locale.US, "%.2f", longestDuration / 60000.0)}\n")
        
        if (workoutsByType.isNotEmpty()) {
            builder.append(context.getString(R.string.csv_breakdown_header))
            
            workoutsByType.forEach { (type, count) ->
                val distance = distanceByType[type] ?: 0f
                val typeName = type.displayName(context)
                builder.append("$typeName,$count,${String.format(Locale.US, "%.2f", distance)}\n")
            }
        }
        
        return builder.toString()
    }
    
    private fun escapeCsv(text: String): String {
        // Если текст содержит запятую, кавычки или перенос строки, заключаем в кавычки
        return if (text.contains(',') || text.contains('"') || text.contains('\n')) {
            "\"${text.replace("\"", "\"\"")}\""
        } else {
            text
        }
    }
    
    /**
     * Получает имя файла для экспорта CSV статистики
     */
    fun getStatisticsCsvFileName(): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val dateStr = dateFormat.format(Date())
        return "statistics_$dateStr.csv"
    }
    
    /**
     * Получает имя файла для экспорта CSV тренировок
     */
    fun getWorkoutsCsvFileName(): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val dateStr = dateFormat.format(Date())
        return "workouts_$dateStr.csv"
    }
}

