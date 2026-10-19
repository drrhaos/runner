package com.runner.academy.util

import android.location.Location
import com.runner.academy.data.ElevationSource
import com.runner.academy.data.PauseInterval
import com.runner.academy.data.TrackData
import com.runner.academy.data.TrackPoint
import com.runner.academy.data.Workout
import com.runner.academy.data.WorkoutType
import com.runner.academy.data.hasAltitude
import java.io.InputStream
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Date

/**
 * Imports one workout from a track file: GPX ([GpxImporter]) or TCX ([TcxImporter]), told
 * apart by the content, so a file with an unusual extension still reads.
 */
object TrackFileImport {

    private val EXTENSIONS = listOf(".gpx", ".tcx")

    /** The TCX root element, with or without a namespace prefix (`<ns2:TrainingCenterDatabase`). */
    private val TCX_ROOT = Regex("""<(\w+:)?TrainingCenterDatabase[\s>]""")

    /** The file name has an extension [parse] reads: what a folder import picks up. */
    fun isTrackFileName(name: String?): Boolean =
        name != null && EXTENSIONS.any { name.endsWith(it, ignoreCase = true) }

    fun parse(input: InputStream, fileName: String? = null): Workout =
        parse(input.bufferedReader().use { it.readText() }, fileName)

    /** Throws [NotRunningException] for another sport, IllegalArgumentException for no workout. */
    fun parse(xml: String, fileName: String? = null): Workout =
        if (TCX_ROOT.containsMatchIn(xml)) {
            TcxImporter.parseTcx(xml, fileName)
        } else {
            GpxImporter.parseGpx(xml, fileName)
        }

    /**
     * A track shorter than this share of the distance the device counted: the GPS froze (one
     * place repeated) and the device measured on its own, from steps or a treadmill.
     */
    private const val MIN_TRACK_SHARE = 0.8

    /**
     * The workout of an [activity] read from [fileName]. With a track (two points or more) its
     * distance and time are the device's totals when it has them, else the track's; without one,
     * or when the track misses much of the counted distance, the totals alone (no track), with
     * the heart rate and cadence of the device. Throws [NotRunningException] for another sport,
     * IllegalArgumentException when there is neither a track nor a distance and time.
     */
    fun workoutOf(activity: ParsedActivity, fileName: String?): Workout {
        if (activity.sport == ActivitySport.OTHER) throw NotRunningException(fileName)
        val workout = trackedWorkout(activity, fileName) ?: totalsWorkout(activity, fileName)
        return workout.copy(
            notes = activity.description?.trim()?.takeIf { it.isNotEmpty() } ?: importNote(fileName),
            title = activity.title?.trim()?.takeIf { it.isNotEmpty() },
            device = activity.device?.trim()?.takeIf { it.isNotEmpty() },
            lapsJson = LapsJson.toJson(activity.laps)
        )
    }

    private fun trackedWorkout(activity: ParsedActivity, fileName: String?): Workout? {
        if (activity.points.size < 2) return null
        val totals = activity.totals
        val tracked = workoutFromPoints(
            activity.points, activity.timeMissing, fileName, totals?.calories, activity.pauses
        )
        val countedM = totals?.distanceM
        if (countedM != null && tracked.distance * 1000.0 < MIN_TRACK_SHARE * countedM) return null
        // The track still draws the map, charts and records; the totals are what the device said
        val distanceKm = countedM?.let { SpeedPaceCalculator.metersToKm(it.toFloat()) } ?: tracked.distance
        val durationMs = totals?.timerMs ?: tracked.duration
        return tracked.copy(
            distance = distanceKm,
            duration = durationMs,
            movingDuration = durationMs,
            avgPace = PaceMath.avgPace(distanceKm, durationMs)
        )
    }

    private fun totalsWorkout(activity: ParsedActivity, fileName: String?): Workout {
        val totals = activity.totals
        val start = activity.startTime ?: activity.points.firstOrNull()?.timestamp
        val distanceM = totals?.distanceM
        val durationMs = totals?.timerMs
        if (start == null || distanceM == null || durationMs == null) {
            throw IllegalArgumentException(
                "Neither a track nor a distance and time${fileName?.let { " ($it)" } ?: ""}"
            )
        }
        val distanceKm = SpeedPaceCalculator.metersToKm(distanceM.toFloat())
        return Workout(
            id = 0,
            date = Date(start),
            distance = distanceKm,
            duration = durationMs,
            // The device's time: its own pauses are already left out
            movingDuration = durationMs,
            avgPace = PaceMath.avgPace(distanceKm, durationMs),
            calories = totals.calories,
            notes = importNote(fileName),
            type = WorkoutType.EASY_RUN,
            trackData = null,
            isFavorite = false,
            // No track to derive them from: the device's values stay
            avgHeartRate = totals.avgHeartRate,
            maxHeartRate = totals.maxHeartRate,
            avgCadence = totals.avgCadence
        )
    }

    /**
     * The workout of a file track: distance and speeds from the [points], the time from the
     * first to the last point minus the [pauses]. [timeMissing]: some point had no time of
     * its own (made up).
     */
    internal fun workoutFromPoints(
        points: List<TrackPoint>,
        timeMissing: Boolean,
        fileName: String?,
        calories: Int? = null,
        pauses: List<PauseInterval>? = null
    ): Workout {
        val metrics = computeMetrics(points, pauses)
        val trackData = TrackData(
            points = points,
            totalDistance = metrics.distanceMeters,
            totalDuration = metrics.durationMs,
            avgSpeed = metrics.avgSpeedMs,
            maxSpeed = metrics.maxSpeedMs,
            startTime = points.first().timestamp,
            endTime = points.last().timestamp,
            pauses = pauses,
            // A point without a time got a made-up one: the track is no record candidate
            timeSynthetic = if (timeMissing) true else null,
            // The altitude is whatever the recording device wrote: shown as "from the file"
            elevationSource = if (points.hasAltitude()) ElevationSource.FILE else null
        )
        val distanceKm = SpeedPaceCalculator.metersToKm(metrics.distanceMeters)

        return Workout(
            id = 0,
            date = Date(points.first().timestamp),
            distance = distanceKm,
            duration = metrics.durationMs,
            // A file has no auto-pause intervals: its pauses stay inside the time (not healed)
            movingDuration = metrics.durationMs,
            avgPace = PaceMath.avgPace(distanceKm, metrics.durationMs),
            calories = calories,
            notes = importNote(fileName),
            type = WorkoutType.EASY_RUN,
            trackData = TrackDataJson.toJson(trackData),
            isFavorite = false
        )
    }

    internal fun importNote(fileName: String?): String? = fileName?.let { "Imported from $it" }

    /** An xsd:dateTime: `Z` or an offset (`+00:00`), with or without fractions; null when unreadable. */
    internal fun parseTime(raw: String): Long? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        // Some files put a space instead of the T
        for (candidate in listOf(text, text.replace(" ", "T"))) {
            try {
                return OffsetDateTime.parse(candidate).toInstant().toEpochMilli()
            } catch (_: Exception) {
            }
            try {
                return Instant.parse(candidate).toEpochMilli()
            } catch (_: Exception) {
            }
        }
        return null
    }

    private data class TrackMetrics(
        val distanceMeters: Float,
        val durationMs: Long,
        val avgSpeedMs: Float,
        val maxSpeedMs: Float
    )

    private fun computeMetrics(points: List<TrackPoint>, pauses: List<PauseInterval>?): TrackMetrics {
        var distanceMeters = 0f
        var maxSpeed = 0f
        val results = FloatArray(1)
        for (i in 1 until points.size) {
            val prev = points[i - 1]
            val curr = points[i]
            Location.distanceBetween(
                prev.latitude,
                prev.longitude,
                curr.latitude,
                curr.longitude,
                results
            )
            distanceMeters += results[0]
            val dtSec = (curr.timestamp - prev.timestamp).coerceAtLeast(1L) / 1000f
            val speed = results[0] / dtSec
            if (speed > maxSpeed) maxSpeed = speed
            curr.speed?.let { if (it > maxSpeed) maxSpeed = it }
        }
        val first = points.first().timestamp
        val last = points.last().timestamp
        val durationMs = (last - first - TrackPauses.overlapMs(pauses, first, last)).coerceAtLeast(0L)
        val avgSpeed = if (durationMs > 0L) {
            distanceMeters / (durationMs / 1000f)
        } else {
            0f
        }
        return TrackMetrics(distanceMeters, durationMs, avgSpeed, maxSpeed)
    }
}
