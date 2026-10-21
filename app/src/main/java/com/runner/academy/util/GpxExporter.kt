package com.runner.academy.util

import android.content.Context
import com.runner.academy.R
import com.runner.academy.data.ElevationSource
import com.runner.academy.data.TrackData
import com.runner.academy.data.Workout
import com.runner.academy.data.WorkoutType
import com.runner.academy.data.displayName
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
        // creator is required by GPX 1.1; TrackPointExtension v1 only for the heart rate
        builder.append("<gpx version=\"$GPX_VERSION\" ")
        builder.append("creator=\"").append(escapeXml(context.getString(R.string.app_name))).append("\" ")
        builder.append("xmlns=\"$GPX_NAMESPACE\" ")
        builder.append("xmlns:gpxtpx=\"http://www.garmin.com/xmlschemas/TrackPointExtension/v1\" ")
        builder.append("xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" ")
        builder.append("xsi:schemaLocation=\"http://www.topografix.com/GPX/1/1 ")
        builder.append("http://www.topografix.com/GPX/1/1/gpx.xsd\">\n")
        
        // Метаданные
        val workoutStartTime = trackData?.points?.firstOrNull()?.timestamp ?: workout.date.time
        builder.append("  <metadata>\n")
        builder.append("    <name>").append(escapeXml(workout.type.displayName(context))).append("</name>\n")
        builder.append("    <time>").append(formatIso8601(Date(workoutStartTime))).append("</time>\n")
        builder.append("  </metadata>\n")
        
        // Трек
        builder.append("  <trk>\n")
        builder.append("    <name>").append(escapeXml(workout.type.displayName(context))).append("</name>\n")

        // Описание: в схеме trk идёт name, cmt, desc, …, type
        val description = buildDescription(workout, context)
        if (description.isNotEmpty()) {
            builder.append("    <desc>").append(escapeXml(description)).append("</desc>\n")
        }
        builder.append("    <type>Running</type>\n")

        // Сегмент трека
        builder.append("    <trkseg>\n")
        
        // The altitudes the details count and draw (ElevationSeries): the barometer's, lifted to
        // the GPS scale, where it is the source — not the GPS ones, which may be far off then
        val altitudes = trackData?.let { ElevationSeries.of(it.points, ElevationSource.of(it)) }

        // Точки трека
        trackData?.points?.forEachIndexed { index, point ->
            builder.append("      <trkpt lat=\"").append(decimal(point.latitude))
                .append("\" lon=\"").append(decimal(point.longitude)).append("\">\n")

            // Высота: только известная (старое 0.0 значит «нет высоты», а не уровень моря)
            altitudes?.get(index)?.let { altitude ->
                builder.append("        <ele>").append(decimal(altitude)).append("</ele>\n")
            }

            // Время
            builder.append("        <time>").append(formatIso8601(Date(point.timestamp))).append("</time>\n")

            // Пульс. В TrackPointExtension v1 только atemp, wtemp, depth, hr, cad: ни точности
            // (метры, а не HDOP), ни скорости там нет, и файл с ними не проходит схему
            point.heartRate?.let { bpm ->
                builder.append("        <extensions>\n")
                builder.append("          <gpxtpx:TrackPointExtension>\n")
                builder.append("            <gpxtpx:hr>").append(bpm).append("</gpxtpx:hr>\n")
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

    /** xsd:decimal: a dot whatever the locale, and never 5.0E-4 near the equator or Greenwich. */
    private fun decimal(value: Double): String {
        val text = value.toString()
        return if ('E' in text) java.math.BigDecimal(text).stripTrailingZeros().toPlainString() else text
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
        val typeStr = workout.type.displayName(context).replace(" ", "_")
        return "workout_${dateStr}_${typeStr}.gpx"
    }
}

