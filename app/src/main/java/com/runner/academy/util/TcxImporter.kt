package com.runner.academy.util

import android.util.Xml
import com.runner.academy.data.LocationSource
import com.runner.academy.data.PauseInterval
import com.runner.academy.data.PauseKind
import com.runner.academy.data.TrackPoint
import com.runner.academy.data.Workout
import com.runner.academy.data.WorkoutType
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.io.StringReader
import java.util.Date
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Imports a single workout from a TCX file (Activities/Activity/Lap/Track/Trackpoint);
 * every activity and lap of the file goes into one workout.
 *
 * With a GPS track (at least two points with a Position) it is imported like a GPX track;
 * the long gaps between points become pauses when the laps say the device paused.
 * Without one (a treadmill run, a watch without GPS), or when the GPS froze and the track
 * misses much of the lap distance, the workout takes the lap totals: distance, time and
 * calories, and has no track; its heart rate stays in the file, since the heart rate
 * columns are derived from a track only.
 */
object TcxImporter {

    /** A gap between two points from which it is a pause (when the laps confirm one). */
    private const val PAUSE_GAP_MS = 60_000L

    /**
     * A track shorter than this share of the lap distance: the GPS froze (one place repeated)
     * and the device counted the distance on its own, from steps or a treadmill.
     */
    private const val MIN_TRACK_SHARE = 0.8

    fun parseTcx(input: InputStream, fileName: String? = null): Workout {
        return parseTcx(input.bufferedReader().use { it.readText() }, fileName)
    }

    fun parseTcx(xml: String, fileName: String? = null): Workout {
        val tcx = parse(xml)
        val calories = tcx.lapCalories.roundToInt().takeIf { it > 0 }
        if (tcx.points.size >= 2) {
            val tracked = TrackFileImport.workoutFromPoints(
                tcx.points, tcx.timeMissing, fileName, calories, pauses = pausesOf(tcx)
            )
            if (tcx.lapMeters <= 0.0 || tracked.distance * 1000.0 >= MIN_TRACK_SHARE * tcx.lapMeters) {
                return tracked
            }
        }
        return fromLapTotals(tcx, fileName, calories)
    }

    /**
     * The time the laps left out of the track (the device paused), as manual pauses: the
     * longest gaps of at least [PAUSE_GAP_MS] between points that fit in it. None when the
     * laps are not shorter than the track by more than that.
     */
    private fun pausesOf(tcx: ParsedTcx): List<PauseInterval>? {
        val spanMs = tcx.points.last().timestamp - tcx.points.first().timestamp
        val lapMs = (tcx.lapSeconds * 1000).roundToLong()
        if (lapMs <= 0L || lapMs > spanMs - PAUSE_GAP_MS) return null
        val leftOutMs = spanMs - lapMs
        val pauses = mutableListOf<PauseInterval>()
        var coveredMs = 0L
        // A sparse track has many long steps: only the longest ones are where it stood
        val gaps = tcx.points.zipWithNext()
            .map { (prev, curr) -> PauseInterval(prev.timestamp, curr.timestamp, PauseKind.MANUAL) }
            .filter { it.end - it.start >= PAUSE_GAP_MS }
            .sortedByDescending { it.end - it.start }
        for (gap in gaps) {
            val gapMs = gap.end - gap.start
            if (coveredMs + gapMs > leftOutMs + PAUSE_GAP_MS) continue
            pauses += gap
            coveredMs += gapMs
            if (coveredMs >= leftOutMs - PAUSE_GAP_MS) break
        }
        return pauses.sortedBy { it.start }.ifEmpty { null }
    }

    private fun fromLapTotals(tcx: ParsedTcx, fileName: String?, calories: Int?): Workout {
        val start = tcx.startTime ?: tcx.points.firstOrNull()?.timestamp
        val durationMs = (tcx.lapSeconds * 1000).roundToLong()
        if (start == null || tcx.lapMeters <= 0.0 || durationMs <= 0L) {
            throw IllegalArgumentException(
                "TCX has neither a track nor a distance and time${fileName?.let { " ($it)" } ?: ""}"
            )
        }
        val distanceKm = SpeedPaceCalculator.metersToKm(tcx.lapMeters.toFloat())
        return Workout(
            id = 0,
            date = Date(start),
            distance = distanceKm,
            duration = durationMs,
            // The lap time is what the device counted: its own pauses are already left out
            movingDuration = durationMs,
            avgPace = PaceMath.avgPace(distanceKm, durationMs),
            calories = calories,
            notes = TrackFileImport.importNote(fileName),
            type = WorkoutType.EASY_RUN,
            trackData = null,
            isFavorite = false
        )
    }

    private class ParsedTcx(
        val points: List<TrackPoint>,
        val timeMissing: Boolean,
        /** The first lap StartTime, else the first activity Id: when the workout began. */
        val startTime: Long?,
        val lapSeconds: Double,
        val lapMeters: Double,
        val lapCalories: Double
    )

    private fun parse(xml: String): ParsedTcx {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(StringReader(xml))

        val points = mutableListOf<TrackPoint>()
        var timeMissing = false
        var lapStart: Long? = null
        var activityId: Long? = null
        var lapSeconds = 0.0
        var lapMeters = 0.0
        var lapCalories = 0.0

        // Local names from the root: the same name (Time, DistanceMeters, Value) means
        // different things under a Lap and under a Trackpoint
        val path = ArrayList<String>()
        var lat: Double? = null
        var lon: Double? = null
        var ele: Double? = null
        var timeMs: Long? = null
        var speed: Float? = null
        var heartRate: Int? = null

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    path.add(parser.name)
                    when (parser.name) {
                        "Lap" -> if (lapStart == null) {
                            lapStart = parser.getAttributeValue(null, "StartTime")?.let(TrackFileImport::parseTime)
                        }
                        "Trackpoint" -> {
                            lat = null
                            lon = null
                            ele = null
                            timeMs = null
                            speed = null
                            heartRate = null
                        }
                    }
                }
                XmlPullParser.TEXT -> {
                    val text = parser.text.trim()
                    val leaf = path.lastOrNull()
                    val parent = path.getOrNull(path.size - 2)
                    if (text.isNotEmpty() && leaf != null) {
                        if ("Trackpoint" in path) {
                            when (leaf) {
                                "Time" -> timeMs = TrackFileImport.parseTime(text)
                                "LatitudeDegrees" -> lat = text.toDoubleOrNull()
                                "LongitudeDegrees" -> lon = text.toDoubleOrNull()
                                "AltitudeMeters" -> ele = text.toDoubleOrNull()
                                // Activity extension TPX/Speed, metres per second
                                "Speed" -> speed = text.toFloatOrNull()?.takeIf { it.isFinite() && it >= 0f }
                                "Value" -> if (parent == "HeartRateBpm") {
                                    heartRate = HeartRates.valid(text.toDoubleOrNull()?.takeIf { it.isFinite() }?.roundToInt())
                                }
                            }
                        } else if (parent == "Lap") {
                            val value = text.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
                            when (leaf) {
                                "TotalTimeSeconds" -> lapSeconds += value
                                "DistanceMeters" -> lapMeters += value
                                "Calories" -> lapCalories += value
                            }
                        } else if (leaf == "Id" && parent == "Activity" && activityId == null) {
                            activityId = TrackFileImport.parseTime(text)
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "Trackpoint") {
                        val latitude = lat
                        val longitude = lon
                        // A point without a Position (a treadmill, a pause in GPS) carries no place
                        if (latitude != null && longitude != null) {
                            if (timeMs == null) timeMissing = true
                            val timestamp = timeMs
                                ?: (points.lastOrNull()?.timestamp?.plus(1000L)
                                    ?: System.currentTimeMillis())
                            points.add(
                                TrackPoint(
                                    latitude = latitude,
                                    longitude = longitude,
                                    timestamp = timestamp,
                                    accuracy = null,
                                    speed = speed,
                                    altitude = ele,
                                    afterGap = false,
                                    source = LocationSource.GPS.name,
                                    heartRate = heartRate
                                )
                            )
                        }
                    }
                    if (path.isNotEmpty()) path.removeAt(path.size - 1)
                }
            }
            event = parser.next()
        }
        return ParsedTcx(points, timeMissing, lapStart ?: activityId, lapSeconds, lapMeters, lapCalories)
    }
}
