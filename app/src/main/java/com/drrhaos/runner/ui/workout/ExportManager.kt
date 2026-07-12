package com.drrhaos.runner.ui.workout

import android.content.Intent
import androidx.core.content.FileProvider
import com.drrhaos.runner.R
import com.drrhaos.runner.data.TrackData
import com.drrhaos.runner.data.Workout
import com.google.gson.Gson

/**
 * Handles data export operations for workout detail screen.
 * Manages GPX export, CSV export coordination, share intent creation,
 * and file provider URI handling.
 */
class ExportManager(
    private val activity: androidx.fragment.app.FragmentActivity
) {

    fun exportWorkoutToGpx(workout: Workout): Boolean {
        try {
            val trackData = parseTrackData(workout.trackData)
            if (trackData == null || trackData.points.isEmpty()) {
                android.widget.Toast.makeText(activity, activity.getString(R.string.track_no_data_export), android.widget.Toast.LENGTH_SHORT).show()
                return false
            }

            val gpxContent = com.drrhaos.runner.util.GpxExporter.exportWorkoutToGpx(workout, trackData, activity)
            saveAndShareGpxFile(gpxContent, workout)
            return true
        } catch (e: Exception) {
            android.util.Log.e("ExportManager", "Error exporting GPX: ${e.message}", e)
            android.widget.Toast.makeText(activity, activity.getString(R.string.export_error, e.message), android.widget.Toast.LENGTH_LONG).show()
            return false
        }
    }

    fun shareWorkout(workout: Workout, formatDuration: (Long) -> String, formatPace: (Float) -> String) {
        val dateFormat = java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.getDefault())
        val duration = formatDuration(workout.duration)
        val pace = formatPace(workout.avgPace)

        val dateStr = dateFormat.format(workout.date)
        val distanceStr = String.format("%.2f", workout.distance)
        val caloriesStr = if (workout.calories != null) "${workout.calories} ккал" else ""
        val shareText = String.format(activity.getString(R.string.workout_share_text), dateStr, distanceStr, duration, pace, caloriesStr)

        val shareIntent = Intent().apply {
            action = Intent.ACTION_SEND
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, shareText)
        }

        activity.startActivity(Intent.createChooser(shareIntent, activity.getString(R.string.share_workout_title)))
    }

    private fun parseTrackData(trackDataJson: String?): TrackData? {
        if (trackDataJson == null) return null
        return try {
            val gson = Gson()
            gson.fromJson(trackDataJson, TrackData::class.java)
        } catch (e: Exception) {
            android.util.Log.e("ExportManager", "Error parsing track data: ${e.message}", e)
            null
        }
    }

    private fun saveAndShareGpxFile(gpxContent: String, workout: Workout) {
        try {
            val fileName = com.drrhaos.runner.util.GpxExporter.getGpxFileName(workout, activity)

            val file = java.io.File.createTempFile(
                fileName.replace(".gpx", ""),
                ".gpx",
                activity.cacheDir
            )

            file.writeText(gpxContent, Charsets.UTF_8)

            val fileUri = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                FileProvider.getUriForFile(
                    activity,
                    "${activity.packageName}.fileprovider",
                    file
                )
            } else {
                @Suppress("DEPRECATION")
                android.net.Uri.fromFile(file)
            }

            val shareIntent = Intent().apply {
                action = Intent.ACTION_SEND
                type = "application/gpx+xml"
                putExtra(Intent.EXTRA_STREAM, fileUri)
                putExtra(Intent.EXTRA_SUBJECT, fileName)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            activity.startActivity(Intent.createChooser(shareIntent, activity.getString(R.string.export_gpx_title)))
            android.widget.Toast.makeText(activity, activity.getString(R.string.gpx_file_ready), android.widget.Toast.LENGTH_SHORT).show()

        } catch (e: Exception) {
            android.util.Log.e("ExportManager", "Error saving GPX file: ${e.message}", e)
            android.widget.Toast.makeText(activity, activity.getString(R.string.file_save_error, e.message), android.widget.Toast.LENGTH_LONG).show()
        }
    }
}
