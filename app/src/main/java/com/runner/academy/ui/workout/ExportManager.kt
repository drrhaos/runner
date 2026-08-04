package com.runner.academy.ui.workout

import android.content.Context
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.runner.academy.R
import com.runner.academy.data.Workout
import com.runner.academy.util.FormatUtils
import com.runner.academy.util.GpxExporter
import com.runner.academy.util.ShareExports
import com.runner.academy.util.TrackDataJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Workout detail export / share. File I/O lives in [ShareExports]; this class
 * only orchestrates GPX generation and UI feedback on a [LifecycleOwner] scope.
 */
class ExportManager(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner
) {

    fun exportWorkoutToGpx(workout: Workout) {
        lifecycleOwner.lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.Default) {
                    val trackData = TrackDataJson.parse(workout.trackData)
                    if (trackData == null || trackData.points.isEmpty()) {
                        null to true
                    } else {
                        GpxExporter.exportWorkoutToGpx(workout, trackData, context) to false
                    }
                }
                val (gpxContent, empty) = result
                if (empty || gpxContent == null) {
                    android.widget.Toast.makeText(
                        context,
                        context.getString(R.string.track_no_data_export),
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                    return@launch
                }
                val fileName = GpxExporter.getGpxFileName(workout, context)
                val file = withContext(Dispatchers.IO) {
                    ShareExports.writeCacheTemp(
                        context,
                        fileName.replace(".gpx", ""),
                        ".gpx",
                        gpxContent
                    )
                }
                ShareExports.shareFile(
                    context = context,
                    file = file,
                    mimeType = "application/gpx+xml",
                    chooserTitle = context.getString(R.string.export_gpx_title),
                    readyMessage = context.getString(R.string.gpx_file_ready)
                )
            } catch (e: OutOfMemoryError) {
                android.util.Log.e(TAG, "OOM exporting GPX", e)
                android.widget.Toast.makeText(
                    context,
                    context.getString(R.string.export_error, e.message),
                    android.widget.Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Error exporting GPX: ${e.message}", e)
                android.widget.Toast.makeText(
                    context,
                    context.getString(R.string.export_error, e.message),
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    fun shareWorkout(workout: Workout) {
        val dateFormat = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
        val caloriesDisplay = workout.calories?.let { calories ->
            context.getString(R.string.gpx_note_calories, calories)
                .substringAfter(':')
                .trim()
        }.orEmpty()
        val shareText = context.getString(
            R.string.workout_share_text,
            dateFormat.format(workout.date),
            String.format(Locale.getDefault(), "%.2f", workout.distance),
            FormatUtils.formatTime(workout.duration),
            FormatUtils.formatPace(workout.avgPace),
            caloriesDisplay
        )
        ShareExports.sharePlainText(
            context,
            shareText,
            context.getString(R.string.share_workout_title)
        )
    }

    companion object {
        private const val TAG = "ExportManager"
    }
}
