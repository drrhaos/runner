package com.runner.academy.util

import android.util.Xml
import com.runner.academy.data.Lap
import com.runner.academy.data.LocationSource
import com.runner.academy.data.PauseInterval
import com.runner.academy.data.PauseKind
import com.runner.academy.data.TrackPoint
import com.runner.academy.data.Workout
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.io.Reader
import java.io.StringReader
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Imports a single workout from a TCX file (Activities/Activity/Lap/Track/Trackpoint);
 * every activity and lap of the file goes into one workout.
 *
 * The distance and time are the sums of the laps (what the device counted). With a GPS track
 * (at least two points with a Position) the track is kept like a GPX one; the long gaps
 * between points become pauses when the laps say the device paused. Without one (a treadmill
 * run, a watch without GPS), or when the GPS froze and the track misses much of the lap
 * distance, the workout has no track and takes the heart rate and cadence of the laps
 * ([TrackFileImport.workoutOf]). Also read: the sport, the laps, `Notes` and the device
 * (`Creator/Name`).
 */
object TcxImporter {

    /** A gap between two points from which it is a pause (when the laps confirm one). */
    private const val PAUSE_GAP_MS = 60_000L

    fun parseTcx(input: InputStream, fileName: String? = null): Workout =
        TrackFileImport.workoutOf(input.bufferedReader().use { parse(it) }, fileName)

    fun parseTcx(xml: String, fileName: String? = null): Workout =
        TrackFileImport.workoutOf(parse(StringReader(xml)), fileName)

    /**
     * The time the laps left out of the track (the device paused), as manual pauses: the
     * longest gaps of at least [PAUSE_GAP_MS] between points that fit in it. None when the
     * laps are not shorter than the track by more than that.
     */
    private fun pausesOf(points: List<TrackPoint>, lapSeconds: Double): List<PauseInterval>? {
        if (points.size < 2) return null
        val spanMs = points.last().timestamp - points.first().timestamp
        val lapMs = (lapSeconds * 1000).roundToLong()
        if (lapMs <= 0L || lapMs > spanMs - PAUSE_GAP_MS) return null
        val leftOutMs = spanMs - lapMs
        val pauses = mutableListOf<PauseInterval>()
        var coveredMs = 0L
        // A sparse track has many long steps: only the longest ones are where it stood
        val gaps = points.zipWithNext()
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

    /** Over the time of each lap that has both; null when none has. */
    private fun timeWeighted(laps: List<Lap>, value: (Lap) -> Double?): Double? {
        var sum = 0.0
        var weight = 0L
        for (lap in laps) {
            val v = value(lap) ?: continue
            val ms = lap.timerMs?.takeIf { it > 0L } ?: continue
            sum += v * ms
            weight += ms
        }
        return if (weight > 0L) sum / weight else null
    }

    /** A lap being read: its values as they come. */
    private class LapReader(val start: Long?) {
        var seconds: Double? = null
        var meters: Double? = null
        var avgHr: Int? = null
        var maxHr: Int? = null
        var runCadence: Double? = null

        fun lap(): Lap? = start?.let {
            Lap(
                start = it,
                timerMs = seconds?.let { s -> (s * 1000).roundToLong() },
                distanceM = meters?.toFloat(),
                avgHr = avgHr,
                maxHr = maxHr,
                // RunCadence counts one foot: steps are twice that
                avgCadence = runCadence?.let { c -> (c * 2).toFloat() }
            )
        }
    }

    fun parse(reader: Reader): ParsedActivity {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(reader)

        val points = mutableListOf<TrackPoint>()
        var timeMissing = false
        var lapStart: Long? = null
        var activityId: Long? = null
        var lapSeconds = 0.0
        var lapMeters = 0.0
        var lapCalories = 0.0
        val sports = mutableListOf<ActivitySport>()
        val laps = mutableListOf<Lap>()
        var lap: LapReader? = null
        var notes: String? = null
        var device: String? = null

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
                        "Activity" -> sports += ActivitySport.ofTcxSport(parser.getAttributeValue(null, "Sport"))
                        "Lap" -> {
                            val start = parser.getAttributeValue(null, "StartTime")?.let(TrackFileImport::parseTime)
                            if (lapStart == null) lapStart = start
                            lap = LapReader(start)
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
                    val grandparent = path.getOrNull(path.size - 3)
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
                            val value = text.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }
                            when (leaf) {
                                "TotalTimeSeconds" -> {
                                    lapSeconds += value ?: 0.0
                                    lap?.seconds = value
                                }
                                "DistanceMeters" -> {
                                    lapMeters += value ?: 0.0
                                    lap?.meters = value
                                }
                                "Calories" -> lapCalories += value ?: 0.0
                            }
                        } else if (leaf == "Value" && grandparent == "Lap") {
                            val bpm = HeartRates.valid(text.toDoubleOrNull()?.takeIf { it.isFinite() }?.roundToInt())
                            when (parent) {
                                "AverageHeartRateBpm" -> lap?.avgHr = bpm
                                "MaximumHeartRateBpm" -> lap?.maxHr = bpm
                            }
                        } else if (leaf == "AvgRunCadence" && "Lap" in path) {
                            lap?.runCadence = text.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }
                        } else if (leaf == "Id" && parent == "Activity" && activityId == null) {
                            activityId = TrackFileImport.parseTime(text)
                        } else if (leaf == "Notes" && parent == "Activity" && notes == null) {
                            notes = text
                        } else if (leaf == "Name" && parent == "Creator" && grandparent == "Activity" && device == null) {
                            device = text
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    when (parser.name) {
                        "Lap" -> {
                            lap?.lap()?.let { laps += it }
                            lap = null
                        }
                        "Trackpoint" -> {
                            val latitude = lat
                            val longitude = lon
                            // A point without a Position (a treadmill, a pause in GPS) carries no place
                            if (latitude != null && longitude != null) {
                                if (timeMs == null) timeMissing = true
                                // A made-up time follows the previous point, or the start the file names:
                                // never "now", so the same file read again is the same workout
                                val timestamp = timeMs
                                    ?: points.lastOrNull()?.timestamp?.plus(1000L)
                                    ?: lapStart
                                    ?: activityId
                                    ?: throw IllegalArgumentException("TCX track point has no time and the activity no start")
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
                    }
                    if (path.isNotEmpty()) path.removeAt(path.size - 1)
                }
            }
            event = parser.next()
        }
        return ParsedActivity(
            points = points,
            timeMissing = timeMissing,
            pauses = pausesOf(points, lapSeconds),
            startTime = lapStart ?: activityId,
            sport = ActivitySport.of(sports),
            description = notes,
            device = device,
            laps = laps,
            totals = ActivityTotals(
                distanceM = lapMeters.takeIf { it > 0.0 },
                timerMs = (lapSeconds * 1000).roundToLong().takeIf { it > 0L },
                calories = lapCalories.roundToInt().takeIf { it > 0 },
                avgHeartRate = timeWeighted(laps) { it.avgHr?.toDouble() }?.roundToInt(),
                maxHeartRate = laps.mapNotNull { it.maxHr }.maxOrNull(),
                avgCadence = timeWeighted(laps) { it.avgCadence?.toDouble() }?.toFloat()
            )
        )
    }
}
