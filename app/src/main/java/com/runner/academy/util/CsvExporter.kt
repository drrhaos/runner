package com.runner.academy.util

import android.content.Context

import com.runner.academy.R
import com.runner.academy.data.Workout
import com.runner.academy.data.WorkoutType
import com.runner.academy.data.displayName
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.*
import kotlin.math.roundToInt

/**
 * Утилита для экспорта статистики тренировок в CSV формат
 */
object CsvExporter {
    
    // Date and time are separate columns, as the header says
    private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")

    /**
     * Экспортирует список тренировок в CSV формат. Метрики без данных (нет высот, нет шагов)
     * — пустые ячейки, а не 0.
     */
    fun exportWorkoutsToCsv(workouts: List<Workout>, context: Context): String {
        val builder = StringBuilder()

        builder.append(context.getString(R.string.csv_header))

        // Данные тренировок
        workouts.forEach { workout ->
            val dateTime = LocalDateTime.ofInstant(workout.date.toInstant(), java.time.ZoneId.systemDefault())
            val date = dateTime.format(dateFormat)
            val time = dateTime.format(timeFormat)
            val type = workout.type.displayName(context)
            val distance = String.format(Locale.US, "%.2f", workout.distance)
            val duration = minutes(workout.duration)
            val pace = if (workout.avgPace > 0) {
                FormatUtils.formatPace(workout.avgPace, context)
            } else {
                "--:--"
            }
            val calories = workout.calories?.toString() ?: ""
            val moving = minutes(workout.movingDuration)
            val gain = whole(workout.elevationGain)
            val loss = whole(workout.elevationLoss)
            val cadence = whole(workout.avgCadence)
            val notes = escapeCsv(workout.notes ?: "")

            builder.append("$date,$time,$type,$distance,$duration,$pace,$calories,$moving,$gain,$loss,$cadence,$notes\n")
        }

        return builder.toString()
    }

    private fun minutes(ms: Long): String = String.format(Locale.US, "%.2f", ms / 60000.0)

    /** Rounded to a whole number; empty without data. */
    private fun whole(value: Float?): String =
        value?.takeIf { it.isFinite() }?.roundToInt()?.toString() ?: ""
    
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
        /** Null: no workout has cadence, the line is left out. */
        averageCadence: Float?,
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
        whole(averageCadence).takeIf { it.isNotEmpty() }?.let { cadence ->
            builder.append("${context.getString(R.string.csv_average_cadence)},$cadence\n")
        }
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
        val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        val dateStr = LocalDateTime.now().format(dateFormatter)
        return "statistics_$dateStr.csv"
    }
    
    /**
     * Получает имя файла для экспорта CSV тренировок
     */
    fun getWorkoutsCsvFileName(): String {
        val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        val dateStr = LocalDateTime.now().format(dateFormatter)
        return "workouts_$dateStr.csv"
    }
}

