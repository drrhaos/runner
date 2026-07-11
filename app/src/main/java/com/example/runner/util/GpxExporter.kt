package com.example.runner.util

import android.content.Context
import com.example.runner.R
import com.example.runner.data.TrackData
import com.example.runner.data.TrackPoint
import com.example.runner.data.Workout
import com.example.runner.data.WorkoutType
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*

/**
 * Утилита для экспорта тренировок в GPX формат
 */
object GpxExporter {
    
    private const val GPX_VERSION = "1.1"
    private const val GPX_NAMESPACE = "http://www.topografix.com/GPX/1/1"
    private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
        .withZone(ZoneId.of("UTC"))
    
    /**
     * Экспортирует тренировку в GPX формат
     */
    fun exportWorkoutToGpx(workout: Workout, trackData: TrackData?, context: Context): String {
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
        builder.append("    <name>").append(escapeXml(getWorkoutTypeName(workout.type, context))).append("</name>\n")
        builder.append("    <time>").append(formatIso8601(Date(workoutStartTime))).append("</time>\n")
        builder.append("  </metadata>\n")
        
        // Трек
        builder.append("  <trk>\n")
        builder.append("    <name>").append(escapeXml(getWorkoutTypeName(workout.type, context))).append("</name>\n")
        builder.append("    <type>Running</type>\n")
        
        // Описание
        val description = buildDescription(workout, context)
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
    
    private fun buildDescription(workout: Workout, context: Context): String {
        val parts = mutableListOf<String>()
        
        parts.add(context.getString(R.string.gpx_note_distance, workout.distance))
        parts.add(context.getString(R.string.gpx_note_time, FormatUtils.formatTime(workout.duration)))
        parts.add(context.getString(R.string.gpx_note_pace, FormatUtils.formatPace(workout.avgPace, context)))
        
        workout.calories?.let { calories ->
            parts.add(context.getString(R.string.gpx_note_calories, calories))
        }
        
        workout.notes?.let { notes ->
            if (notes.isNotEmpty()) {
                parts.add(context.getString(R.string.gpx_note_notes, notes))
            }
        }
        
        return parts.joinToString("; ")
    }
    
    private fun getWorkoutTypeName(type: WorkoutType, context: Context): String {
        return when (type) {
            WorkoutType.EASY_RUN -> context.getString(R.string.workout_type_easy_run)
            WorkoutType.TEMPO_RUN -> context.getString(R.string.workout_type_tempo_run)
            WorkoutType.INTERVAL_TRAINING -> context.getString(R.string.workout_type_interval_training)
            WorkoutType.LONG_RUN -> context.getString(R.string.workout_type_long_run)
            WorkoutType.RECOVERY_RUN -> context.getString(R.string.workout_type_recovery_run)
            WorkoutType.RACE -> context.getString(R.string.workout_type_competition)
        }
    }
    
    private fun formatIso8601(date: Date): String {
        return dateFormat.format(Instant.ofEpochMilli(date.time))
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
    fun getGpxFileName(workout: Workout, context: Context): String {
        val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm")
        val dateStr = LocalDateTime.ofInstant(workout.date.toInstant(), ZoneId.systemDefault())
            .format(dateFormatter)
        val typeStr = getWorkoutTypeName(workout.type, context).replace(" ", "_")
        return "workout_${dateStr}_${typeStr}.gpx"
    }
}

