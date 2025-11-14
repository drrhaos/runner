package com.example.runner.util

import com.example.runner.data.TrackData
import com.example.runner.data.TrackPoint
import com.example.runner.data.Workout
import com.example.runner.data.WorkoutType
import java.text.SimpleDateFormat
import java.util.*

/**
 * Утилита для экспорта тренировок в GPX формат
 */
object GpxExporter {
    
    private const val GPX_VERSION = "1.1"
    private const val GPX_NAMESPACE = "http://www.topografix.com/GPX/1/1"
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    
    /**
     * Экспортирует тренировку в GPX формат
     */
    fun exportWorkoutToGpx(workout: Workout, trackData: TrackData?): String {
        val builder = StringBuilder()
        
        // GPX заголовок
        builder.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        builder.append("<gpx version=\"$GPX_VERSION\" ")
        builder.append("xmlns=\"$GPX_NAMESPACE\" ")
        builder.append("xmlns:gpxtpx=\"http://www.garmin.com/xmlschemas/TrackPointExtension/v1\" ")
        builder.append("xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" ")
        builder.append("xsi:schemaLocation=\"http://www.topografix.com/GPX/1/1 ")
        builder.append("http://www.topografix.com/GPX/1/1/gpx.xsd\">\n")
        
        // Метаданные
        val workoutStartTime = trackData?.points?.firstOrNull()?.timestamp ?: workout.date.time
        builder.append("  <metadata>\n")
        builder.append("    <name>").append(escapeXml(getWorkoutTypeName(workout.type))).append("</name>\n")
        builder.append("    <time>").append(formatIso8601(Date(workoutStartTime))).append("</time>\n")
        builder.append("  </metadata>\n")
        
        // Трек
        builder.append("  <trk>\n")
        builder.append("    <name>").append(escapeXml(getWorkoutTypeName(workout.type))).append("</name>\n")
        builder.append("    <type>Running</type>\n")
        
        // Описание
        val description = buildDescription(workout)
        if (description.isNotEmpty()) {
            builder.append("    <desc>").append(escapeXml(description)).append("</desc>\n")
        }
        
        // Сегмент трека
        builder.append("    <trkseg>\n")
        
        // Точки трека
        trackData?.points?.forEach { point ->
            builder.append("      <trkpt lat=\"").append(point.latitude)
                .append("\" lon=\"").append(point.longitude).append("\">\n")
            
            // Высота
            point.altitude?.let { altitude ->
                builder.append("        <ele>").append(altitude).append("</ele>\n")
            }
            
            // Время
            builder.append("        <time>").append(formatIso8601(Date(point.timestamp))).append("</time>\n")
            
            // Расширения (точность, скорость)
            if (point.accuracy != null || point.speed != null) {
                builder.append("        <extensions>\n")
                builder.append("          <gpxtpx:TrackPointExtension>\n")
                
                point.accuracy?.let { accuracy ->
                    builder.append("            <gpxtpx:hdop>").append(String.format("%.1f", accuracy)).append("</gpxtpx:hdop>\n")
                }
                
                point.speed?.let { speed ->
                    builder.append("            <gpxtpx:speed>").append(String.format("%.2f", speed)).append("</gpxtpx:speed>\n")
                }
                
                builder.append("          </gpxtpx:TrackPointExtension>\n")
                builder.append("        </extensions>\n")
            }
            
            builder.append("      </trkpt>\n")
        }
        
        builder.append("    </trkseg>\n")
        builder.append("  </trk>\n")
        builder.append("</gpx>\n")
        
        return builder.toString()
    }
    
    private fun buildDescription(workout: Workout): String {
        val parts = mutableListOf<String>()
        
        parts.add("Дистанция: ${String.format("%.2f", workout.distance)} км")
        parts.add("Время: ${FormatUtils.formatTime(workout.duration)}")
        parts.add("Темп: ${FormatUtils.formatPace(workout.avgPace)}")
        
        workout.calories?.let { calories ->
            parts.add("Калории: $calories ккал")
        }
        
        workout.notes?.let { notes ->
            if (notes.isNotEmpty()) {
                parts.add("Заметки: $notes")
            }
        }
        
        return parts.joinToString("; ")
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
    
    private fun formatIso8601(date: Date): String {
        return dateFormat.format(date)
    }
    
    private fun escapeXml(text: String): String {
        return text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
    
    /**
     * Получает имя файла для экспорта GPX
     */
    fun getGpxFileName(workout: Workout): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.getDefault())
        val dateStr = dateFormat.format(workout.date)
        val typeStr = getWorkoutTypeName(workout.type).replace(" ", "_")
        return "workout_${dateStr}_${typeStr}.gpx"
    }
}

