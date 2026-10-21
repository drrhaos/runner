package com.runner.academy.util

import android.util.Xml
import com.runner.academy.data.LocationSource
import com.runner.academy.data.TrackPoint
import com.runner.academy.data.Workout
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.io.Reader
import java.io.StringReader

/**
 * Imports a single workout from a GPX 1.1 track (trk/trkseg/trkpt): its points, the name
 * (`trk/name`, else `metadata/name`), the description (`desc`, likewise) and the sport
 * (`trk/type`). The creator is not a device: mostly the app or service that wrote the file.
 */
object GpxImporter {

    fun parseGpx(input: InputStream, fileName: String? = null): Workout =
        TrackFileImport.workoutOf(input.bufferedReader().use { parse(it) }, fileName)

    fun parseGpx(xml: String, fileName: String? = null): Workout =
        TrackFileImport.workoutOf(parse(StringReader(xml)), fileName)

    fun parse(reader: Reader): ParsedActivity {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(reader)

        val points = mutableListOf<TrackPoint>()
        var timeMissing = false
        // Local names from the root: a name under trk is the run's, under trkpt a point's
        val path = ArrayList<String>()
        val text = StringBuilder()
        var trackName: String? = null
        var metadataName: String? = null
        var trackDesc: String? = null
        var metadataDesc: String? = null
        var type: String? = null
        var lat: Double? = null
        var lon: Double? = null
        var ele: Double? = null
        var timeMs: Long? = null
        var speed: Float? = null
        var heartRate: Int? = null
        var inTrkpt = false

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    path.add(parser.name)
                    text.setLength(0)
                    if (parser.name == "trkpt") {
                        inTrkpt = true
                        lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull()
                        lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull()
                        ele = null
                        timeMs = null
                        speed = null
                        heartRate = null
                    }
                }
                XmlPullParser.TEXT -> text.append(parser.text)
                XmlPullParser.END_TAG -> {
                    val value = text.toString().trim()
                    val parent = path.getOrNull(path.size - 2)
                    when (parser.name) {
                        "ele" -> if (inTrkpt) ele = value.toDoubleOrNull()
                        "time" -> if (inTrkpt) timeMs = TrackFileImport.parseTime(value)
                        "speed" -> if (inTrkpt) speed = value.toFloatOrNull()
                        // The local name: gpxtpx:hr, ns3:hr and other prefixes alike
                        "hr" -> if (inTrkpt) heartRate = HeartRates.valid(value.toIntOrNull())
                        "name" -> when (parent) {
                            "trk" -> if (trackName == null) trackName = value
                            "metadata" -> if (metadataName == null) metadataName = value
                        }
                        "desc" -> when (parent) {
                            "trk" -> if (trackDesc == null) trackDesc = value
                            "metadata" -> if (metadataDesc == null) metadataDesc = value
                        }
                        "type" -> if (parent == "trk" && type == null) type = value
                        "trkpt" -> if (inTrkpt) {
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
                    if (path.isNotEmpty()) path.removeAt(path.size - 1)
                    text.setLength(0)
                }
            }
            event = parser.next()
        }
        return ParsedActivity(
            points = points,
            timeMissing = timeMissing,
            sport = ActivitySport.ofGpxType(type),
            title = trackName?.takeIf { it.isNotEmpty() } ?: metadataName,
            description = trackDesc?.takeIf { it.isNotEmpty() } ?: metadataDesc
        )
    }
}
