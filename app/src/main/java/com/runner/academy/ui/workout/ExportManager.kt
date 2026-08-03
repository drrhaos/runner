package com.runner.academy.ui.workout

import android.content.Intent
import androidx.core.content.FileProvider
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.runner.academy.R
import com.runner.academy.data.TrackData
import com.runner.academy.data.Workout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Handles data export operations for workout detail screen.
 * Manages GPX export, CSV export coordination, share intent creation,
 * and file provider URI handling.
 */
class ExportManager(
    private val activity: FragmentActivity
) {

    fun exportWorkoutToGpx(workout: Workout) {
        activity.lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.Default) {
                    val trackData = parseTrackData(workout.trackData)
                    if (trackData == null || trackData.points.isEmpty()) {
                        null to true
                    } else {
                        val gpxContent = com.runner.academy.util.GpxExporter.exportWorkoutToGpx(
                            workout,
                            trackData,
                            activity
                        )
                        gpxContent to false
                    }
                }
                val (gpxContent, empty) = result
                if (empty || gpxContent == null) {
                    android.widget.Toast.makeText(
                        activity,
                        activity.getString(R.string.track_no_data_export),
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                    return@launch
                }
                withContext(Dispatchers.IO) {
                    saveGpxFile(gpxContent, workout)
                }?.let { (file, fileName) ->
                    shareGpxFile(file, fileName)
                }
            } catch (e: OutOfMemoryError) {
                android.util.Log.e("ExportManager", "OOM exporting GPX", e)
                android.widget.Toast.makeText(
                    activity,
                    activity.getString(R.string.export_error, e.message),
                    android.widget.Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                android.util.Log.e("ExportManager", "Error exporting GPX: ${e.message}", e)
                android.widget.Toast.makeText(
                    activity,
                    activity.getString(R.string.export_error, e.message),
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    fun shareWorkout(workout: Workout, formatDuration: (Long) -> String, formatPace: (Float) -> String) {
        val dateFormat = java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.getDefault())
        val duration = formatDuration(workout.duration)
        val pace = formatPace(workout.avgPace)

        val dateStr = dateFormat.format(workout.date)
        val distanceStr = String.format("%.2f", workout.distance)
        val caloriesStr = if (workout.calories != null) "${workout.calories} ккал" else ""
        val shareText = String.format(
            activity.getString(R.string.workout_share_text),
            dateStr,
            distanceStr,
            duration,
            pace,
            caloriesStr
        )

        val shareIntent = Intent().apply {
            action = Intent.ACTION_SEND
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, shareText)
        }

        activity.startActivity(
            Intent.createChooser(shareIntent, activity.getString(R.string.share_workout_title))
        )
    }

    private fun parseTrackData(trackDataJson: String?): TrackData? {
        return com.runner.academy.util.TrackDataJson.parse(trackDataJson)
    }

    private fun saveGpxFile(gpxContent: String, workout: Workout): Pair<java.io.File, String>? {
        return try {
            val fileName = com.runner.academy.util.GpxExporter.getGpxFileName(workout, activity)
            val file = java.io.File.createTempFile(
                fileName.replace(".gpx", ""),
                ".gpx",
                activity.cacheDir
            )
            file.writeText(gpxContent, Charsets.UTF_8)
            file to fileName
        } catch (e: Exception) {
            android.util.Log.e("ExportManager", "Error saving GPX file: ${e.message}", e)
            null
        }
    }

    private fun shareGpxFile(file: java.io.File, fileName: String) {
        try {
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

            activity.startActivity(
                Intent.createChooser(shareIntent, activity.getString(R.string.export_gpx_title))
            )
            android.widget.Toast.makeText(
                activity,
                activity.getString(R.string.gpx_file_ready),
                android.widget.Toast.LENGTH_SHORT
            ).show()
        } catch (e: Exception) {
            android.util.Log.e("ExportManager", "Error sharing GPX file: ${e.message}", e)
            android.widget.Toast.makeText(
                activity,
                activity.getString(R.string.file_save_error, e.message),
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }
}
