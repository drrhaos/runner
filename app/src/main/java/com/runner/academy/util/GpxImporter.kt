package com.runner.academy.util

import android.util.Xml
import com.runner.academy.data.LocationSource
import com.runner.academy.data.TrackPoint
import com.runner.academy.data.Workout
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.io.StringReader

/**
 * Imports a single workout from a GPX 1.1 track (trk/trkseg/trkpt).
 */
object GpxImporter {

    fun parseGpx(input: InputStream, fileName: String? = null): Workout {
        return parseGpx(input.bufferedReader().use { it.readText() }, fileName)
    }

    fun parseGpx(xml: String, fileName: String? = null): Workout {
        val (points, timeMissing) = parseTrackPoints(xml)
        if (points.size < 2) {
            throw IllegalArgumentException(
                "GPX has too few track points${fileName?.let { " ($it)" } ?: ""}"
            )
        }
        return TrackFileImport.workoutFromPoints(points, timeMissing, fileName)
    }

    /** The track points, and whether any of them had no `<time>` (its time is made up). */
    private fun parseTrackPoints(xml: String): Pair<List<TrackPoint>, Boolean> {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(StringReader(xml))

        val points = mutableListOf<TrackPoint>()
        var timeMissing = false
        var event = parser.eventType
        var lat: Double? = null
        var lon: Double? = null
        var ele: Double? = null
        var timeMs: Long? = null
        var speed: Float? = null
        var heartRate: Int? = null
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
                            heartRate = null
                        }
                        "ele" -> if (inTrkpt) {
                            ele = parser.nextText().toDoubleOrNull()
                        }
                        "time" -> if (inTrkpt) {
                            timeMs = TrackFileImport.parseTime(parser.nextText())
                        }
                        "speed" -> if (inTrkpt) {
                            speed = parser.nextText().toFloatOrNull()
                        }
                        // The local name: gpxtpx:hr, ns3:hr and other prefixes alike
                        "hr" -> if (inTrkpt) {
                            heartRate = HeartRates.valid(parser.nextText().trim().toIntOrNull())
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "trkpt" && inTrkpt) {
                        val latitude = lat
                        val longitude = lon
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
                        inTrkpt = false
                    }
                }
            }
            event = parser.next()
        }
        return points to timeMissing
    }
}
