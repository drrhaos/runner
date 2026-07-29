package com.runner.academy.util

import android.location.Location
import android.util.Xml
import com.google.gson.Gson
import com.runner.academy.data.LocationSource
import com.runner.academy.data.TrackData
import com.runner.academy.data.TrackPoint
import com.runner.academy.data.Workout
import com.runner.academy.data.WorkoutType
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.io.StringReader
import java.time.Instant
import java.util.Date

/**
 * Imports a single workout from a GPX 1.1 track (trk/trkseg/trkpt).
 */
object GpxImporter {

    private val gson = Gson()

    fun parseGpx(input: InputStream, fileName: String? = null): Workout {
        return parseGpx(input.bufferedReader().use { it.readText() }, fileName)
    }

    fun parseGpx(xml: String, fileName: String? = null): Workout {
        val points = parseTrackPoints(xml)
        if (points.size < 2) {
            throw IllegalArgumentException(
                "GPX has too few track points${fileName?.let { " ($it)" } ?: ""}"
            )
        }

        val metrics = computeMetrics(points)
        val trackData = TrackData(
            points = points,
            totalDistance = metrics.distanceMeters,
            totalDuration = metrics.durationMs,
            avgSpeed = metrics.avgSpeedMs,
            maxSpeed = metrics.maxSpeedMs,
            startTime = points.first().timestamp,
            endTime = points.last().timestamp
        )
        val distanceKm = SpeedPaceCalculator.metersToKm(metrics.distanceMeters)
        val avgPace = if (distanceKm > 0f && metrics.durationMs > 0L) {
            (metrics.durationMs / 60_000f) / distanceKm
        } else {
            0f
        }

        return Workout(
            id = 0,
            date = Date(points.first().timestamp),
            distance = distanceKm,
            duration = metrics.durationMs,
            avgPace = avgPace,
            calories = null,
            notes = fileName?.let { "Imported from $it" },
            type = WorkoutType.EASY_RUN,
            trackData = gson.toJson(trackData),
            isFavorite = false
        )
    }

    private data class TrackMetrics(
        val distanceMeters: Float,
        val durationMs: Long,
        val avgSpeedMs: Float,
        val maxSpeedMs: Float
    )

    private fun computeMetrics(points: List<TrackPoint>): TrackMetrics {
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
        val durationMs = (points.last().timestamp - points.first().timestamp).coerceAtLeast(0L)
        val avgSpeed = if (durationMs > 0L) {
            distanceMeters / (durationMs / 1000f)
        } else {
            0f
        }
        return TrackMetrics(distanceMeters, durationMs, avgSpeed, maxSpeed)
    }

    private fun parseTrackPoints(xml: String): List<TrackPoint> {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(StringReader(xml))

        val points = mutableListOf<TrackPoint>()
        var event = parser.eventType
        var lat: Double? = null
        var lon: Double? = null
        var ele: Double? = null
        var timeMs: Long? = null
        var speed: Float? = null
        var inTrkpt = false

        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "trkpt" -> {
                            inTrkpt = true
                            lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull()
                            lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull()
                            ele = null
                            timeMs = null
                            speed = null
                        }
                        "ele" -> if (inTrkpt) {
                            ele = parser.nextText().toDoubleOrNull()
                        }
                        "time" -> if (inTrkpt) {
                            timeMs = parseGpxTime(parser.nextText())
                        }
                        "speed" -> if (inTrkpt) {
                            speed = parser.nextText().toFloatOrNull()
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "trkpt" && inTrkpt) {
                        val latitude = lat
                        val longitude = lon
                        if (latitude != null && longitude != null) {
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
                                    source = LocationSource.GPS.name
                                )
                            )
                        }
                        inTrkpt = false
                    }
                }
            }
            event = parser.next()
        }
        return points
    }

    private fun parseGpxTime(raw: String): Long? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        return try {
            Instant.parse(text).toEpochMilli()
        } catch (_: Exception) {
            try {
                // Some files omit milliseconds or use slight variants
                Instant.parse(text.replace(" ", "T")).toEpochMilli()
            } catch (_: Exception) {
                null
            }
        }
    }
}
