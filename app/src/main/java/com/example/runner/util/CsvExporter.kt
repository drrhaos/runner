package com.example.runner.util

import com.example.runner.data.Workout
import com.example.runner.data.WorkoutType
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
    fun exportWorkoutsToCsv(workouts: List<Workout>): String {
        val builder = StringBuilder()
        
        // Заголовки CSV
        builder.append("Дата,Время,Тип тренировки,Дистанция (км),Длительность (мин),Темп (мин/км),Калории,Заметки\n")
        
        // Данные тренировок
        workouts.forEach { workout ->
            val date = dateFormat.format(workout.date)
            val type = getWorkoutTypeName(workout.type)
            val distance = String.format(Locale.US, "%.2f", workout.distance)
            val durationMinutes = workout.duration / 60000.0
            val duration = String.format(Locale.US, "%.2f", durationMinutes)
            val pace = if (workout.avgPace > 0) {
                FormatUtils.formatPace(workout.avgPace)
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
        distanceByType: Map<WorkoutType, Float>
    ): String {
        val builder = StringBuilder()
        
        // Общая статистика
        builder.append("Статистика тренировок\n\n")
        builder.append("Категория,Значение\n")
        builder.append("Всего тренировок,$totalWorkouts\n")
        builder.append("Общая дистанция (км),${String.format(Locale.US, "%.2f", totalDistance)}\n")
        builder.append("Общее время (часы),${String.format(Locale.US, "%.2f", totalDuration / 3600000.0)}\n")
        builder.append("Всего калорий,$totalCalories\n")
        builder.append("Средний темп (мин/км),${if (averagePace > 0) FormatUtils.formatPace(averagePace) else "--:--"}\n")
        builder.append("Средняя дистанция (км),${String.format(Locale.US, "%.2f", averageDistance)}\n")
        builder.append("Среднее время (минуты),${String.format(Locale.US, "%.2f", averageDuration / 60000.0)}\n")
        builder.append("Лучший темп (мин/км),${if (bestPace > 0) FormatUtils.formatPace(bestPace) else "--:--"}\n")
        builder.append("Максимальная дистанция (км),${String.format(Locale.US, "%.2f", longestDistance)}\n")
        builder.append("Самая длинная тренировка (минуты),${String.format(Locale.US, "%.2f", longestDuration / 60000.0)}\n")
        
        // Статистика по типам тренировок
        if (workoutsByType.isNotEmpty()) {
            builder.append("\nРаспределение по типам тренировок\n")
            builder.append("Тип тренировки,Количество,Дистанция (км)\n")
            
            workoutsByType.forEach { (type, count) ->
                val distance = distanceByType[type] ?: 0f
                val typeName = getWorkoutTypeName(type)
                builder.append("$typeName,$count,${String.format(Locale.US, "%.2f", distance)}\n")
            }
        }
        
        return builder.toString()
    }
    
    private fun getWorkoutTypeName(type: WorkoutType): String {
        return when (type) {
            WorkoutType.EASY_RUN -> "Легкий бег"
            WorkoutType.TEMPO_RUN -> "Темповый бег"
            WorkoutType.INTERVAL_TRAINING -> "Интервальная тренировка"
            WorkoutType.LONG_RUN -> "Длинный бег"
            WorkoutType.RECOVERY_RUN -> "Восстановительный бег"
            WorkoutType.RACE -> "Соревнование"
        }
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

