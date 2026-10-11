package com.runner.academy.util

import androidx.test.core.app.ApplicationProvider
import com.runner.academy.data.TrackData
import com.runner.academy.data.TrackPoint
import com.runner.academy.data.Workout
import com.runner.academy.data.WorkoutType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Date

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class GpxExporterTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun `only known altitudes are exported, the old 0_0 for none is not`() {
        val points = listOf(152.5, 0.0, null, 153.0).mapIndexed { i, alt ->
            TrackPoint(55.75, 37.60 + i * 0.0001, 1_800_000_000_000L + i * 1_000L, 5f, 3f, alt)
        }
        val track = TrackData(points, 0f, 3_000L, 0f, 0f, points.first().timestamp, points.last().timestamp)
        val workout = Workout(
            date = Date(points.first().timestamp), distance = 0.02f, duration = 3_000L, movingDuration = 3_000L,
            avgPace = 6f, calories = null, notes = null, type = WorkoutType.EASY_RUN
        )

        val gpx = GpxExporter.exportWorkoutToGpx(workout, track, context)

        val elevations = Regex("<ele>([^<]*)</ele>").findAll(gpx).map { it.groupValues[1] }.toList()
        assertEquals(listOf("152.5", "153.0"), elevations)
    }

    private fun workoutOf(points: List<TrackPoint>) = Workout(
        date = Date(points.first().timestamp), distance = 0.02f, duration = 3_000L, movingDuration = 3_000L,
        avgPace = 6f, calories = null, notes = null, type = WorkoutType.EASY_RUN
    )

    private fun trackOf(points: List<TrackPoint>) =
        TrackData(points, 0f, 3_000L, 0f, 0f, points.first().timestamp, points.last().timestamp)

    @Test
    fun `the heart rate survives an export and an import`() {
        val points = listOf(148, null, 152, 155).mapIndexed { i, hr ->
            TrackPoint(55.75, 37.60 + i * 0.0001, 1_800_000_000_000L + i * 1_000L, 5f, 3f, 150.0, heartRate = hr)
        }

        val gpx = GpxExporter.exportWorkoutToGpx(workoutOf(points), trackOf(points), context)
        val imported = TrackDataJson.parse(GpxImporter.parseGpx(gpx).trackData)!!

        assertEquals(listOf(148, null, 152, 155), imported.points.map { it.heartRate })
    }

    @Test
    fun `an imported file with heart rate gets its average and maximum derived`() {
        // 2 min, a fix a second, 150 with a 170 stretch of 10 s
        val points = (0..120).map { sec ->
            TrackPoint(
                55.75, 37.60 + sec * 0.00004, 1_800_000_000_000L + sec * 1_000L, 5f, 3f, 150.0,
                heartRate = if (sec in 60..69) 170 else 150
            )
        }
        val gpx = GpxExporter.exportWorkoutToGpx(workoutOf(points), trackOf(points), context)
        val imported = GpxImporter.parseGpx(gpx)

        val derived = WorkoutDerivation.derive(DerivationInput(imported.trackData, imported.type, imported.duration))

        assertEquals(152f, derived.avgHeartRate!!.toFloat(), 1f)
        assertEquals(170, derived.maxHeartRate)
    }

    @Test
    fun `the heart rate is the first element of the extension, alone it still opens one`() {
        val points = listOf(
            TrackPoint(55.75, 37.60, 1_800_000_000_000L, 5f, 3f, null, heartRate = 150),
            TrackPoint(55.75, 37.6001, 1_800_000_001_000L, null, null, null, heartRate = 151)
        )

        val gpx = GpxExporter.exportWorkoutToGpx(workoutOf(points), trackOf(points), context)

        // TrackPointExtension v1 is a sequence: atemp, wtemp, depth, hr, cad
        assertTrue(gpx.contains("<gpxtpx:TrackPointExtension>\n            <gpxtpx:hr>150</gpxtpx:hr>\n            <gpxtpx:hdop>"))
        assertTrue(gpx.contains("<gpxtpx:TrackPointExtension>\n            <gpxtpx:hr>151</gpxtpx:hr>\n          </gpxtpx:TrackPointExtension>"))
    }

    @Test
    fun `a point without heart rate exports no hr`() {
        val points = listOf(
            TrackPoint(55.75, 37.60, 1_800_000_000_000L, null, null, null),
            TrackPoint(55.75, 37.6001, 1_800_000_001_000L, null, null, null)
        )

        val gpx = GpxExporter.exportWorkoutToGpx(workoutOf(points), trackOf(points), context)

        assertFalse(gpx.contains("hr>"))
        assertFalse(gpx.contains("<extensions>"))
    }

    private fun gpxWith(vararg hr: String) = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?>""")
        append("""<gpx version="1.1" creator="Some Service" xmlns="http://www.topografix.com/GPX/1/1" """)
        append("""xmlns:ns3="http://www.garmin.com/xmlschemas/TrackPointExtension/v1"><trk><trkseg>""")
        hr.forEachIndexed { i, value ->
            append("""<trkpt lat="55.75" lon="${37.60 + i * 0.0001}"><ele>150.0</ele>""")
            append("<time>2026-10-11T07:00:0${i}Z</time>")
            append("<extensions><ns3:TrackPointExtension><ns3:hr>$value</ns3:hr><ns3:cad>85</ns3:cad>")
            append("</ns3:TrackPointExtension></extensions></trkpt>")
        }
        append("</trkseg></trk></gpx>")
    }

    @Test
    fun `an ns3 heart rate is read`() {
        val imported = TrackDataJson.parse(GpxImporter.parseGpx(gpxWith("141", " 142 ", "143")).trackData)!!

        assertEquals(listOf(141, 142, 143), imported.points.map { it.heartRate })
    }

    @Test
    fun `an implausible or broken heart rate is imported as none`() {
        val imported = TrackDataJson.parse(GpxImporter.parseGpx(gpxWith("0", "251", "x", "24", "25")).trackData)!!

        assertEquals(listOf(null, null, null, null, 25), imported.points.map { it.heartRate })
    }
}
