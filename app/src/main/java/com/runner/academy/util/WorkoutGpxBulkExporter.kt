package com.runner.academy.util

import android.content.Context
import com.google.gson.Gson
import com.runner.academy.data.TrackData
import com.runner.academy.data.Workout
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Builds a ZIP archive with one GPX file per workout that has track points.
 */
object WorkoutGpxBulkExporter {

    private val gson = Gson()
    private val fileDateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss")
        .withZone(ZoneOffset.UTC)

    data class GpxZipResult(
        val zipFile: File,
        val exportedCount: Int,
        val skippedWithoutTrack: Int
    )

    fun exportToZip(workouts: List<Workout>, context: Context, targetDir: File): GpxZipResult {
        targetDir.mkdirs()
        val zipFile = File(targetDir, getZipFileName())
        var exported = 0
        var skipped = 0
        val usedNames = mutableSetOf<String>()

        ZipOutputStream(zipFile.outputStream().buffered()).use { zip ->
            workouts.forEach { workout ->
                val trackData = parseTrackData(workout.trackData)
                if (trackData == null || trackData.points.isEmpty()) {
                    skipped++
                    return@forEach
                }
                val baseName = GpxExporter.getGpxFileName(workout, context)
                val entryName = uniqueName(baseName, usedNames)
                val gpx = GpxExporter.exportWorkoutToGpx(workout, trackData, context)
                zip.putNextEntry(ZipEntry(entryName))
                zip.write(gpx.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                exported++
            }
        }

        if (exported == 0) {
            zipFile.delete()
            throw IllegalStateException("No workouts with track data to export")
        }
        return GpxZipResult(zipFile, exported, skipped)
    }

    fun getZipFileName(): String {
        val stamp = fileDateFormat.format(Instant.now())
        return "runner_workouts_gpx_$stamp.zip"
    }

    private fun uniqueName(preferred: String, used: MutableSet<String>): String {
        if (used.add(preferred)) return preferred
        val stem = preferred.substringBeforeLast('.', preferred)
        val ext = preferred.substringAfterLast('.', "gpx")
        var i = 2
        while (true) {
            val candidate = "${stem}_$i.$ext"
            if (used.add(candidate)) return candidate
            i++
        }
    }

    private fun parseTrackData(json: String?): TrackData? {
        if (json.isNullOrBlank()) return null
        return try {
            gson.fromJson(json, TrackData::class.java)
        } catch (_: Exception) {
            null
        }
    }
}
