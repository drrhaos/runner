package com.runner.academy.util

import com.runner.academy.data.ElevationSource
import com.runner.academy.data.WorkoutType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TcxImporterTest {

    private fun tcx(vararg laps: String) = """
        <?xml version="1.0" encoding="UTF-8"?>
        <TrainingCenterDatabase xmlns="http://www.garmin.com/xmlschemas/TrainingCenterDatabase/v2"
            xmlns:ns3="http://www.garmin.com/xmlschemas/ActivityExtension/v2"
            xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
          <Activities><Activity Sport="Running">
            <Id>2018-01-23T07:00:00+00:00</Id>
            ${laps.joinToString("\n")}
          </Activity></Activities>
        </TrainingCenterDatabase>
    """.trim()

    private fun lap(start: String, seconds: Double, meters: Double, calories: Double, track: String = "") = """
        <Lap StartTime="$start">
          <TotalTimeSeconds>$seconds</TotalTimeSeconds>
          <DistanceMeters>$meters</DistanceMeters>
          <Calories>$calories</Calories>
          <AverageHeartRateBpm xsi:type="HeartRateInBeatsPerMinute_t"><Value>150.0</Value></AverageHeartRateBpm>
          <Track>$track</Track>
        </Lap>
    """

    private fun point(time: String, lat: Double?, lon: Double?, ele: Double? = null, hr: String? = null, speed: String? = null) = """
        <Trackpoint>
          <Time>$time</Time>
          ${if (lat != null && lon != null) "<Position><LatitudeDegrees>$lat</LatitudeDegrees><LongitudeDegrees>$lon</LongitudeDegrees></Position>" else ""}
          ${ele?.let { "<AltitudeMeters>$it</AltitudeMeters>" } ?: ""}
          <DistanceMeters>12.5</DistanceMeters>
          ${hr?.let { "<HeartRateBpm xsi:type=\"HeartRateInBeatsPerMinute_t\"><Value>$it</Value></HeartRateBpm>" } ?: ""}
          ${speed?.let { "<Extensions><ns3:TPX><ns3:Speed>$it</ns3:Speed></ns3:TPX></Extensions>" } ?: ""}
        </Trackpoint>
    """

    @Test
    fun gpsTrack_isImportedWithPointsHeartRateAndAltitude() {
        val xml = tcx(
            lap(
                "2018-01-23T07:00:00+00:00", 600.0, 260.0, 120.0,
                point("2018-01-23T07:00:00+00:00", 55.7558, 37.6176, 150.0, "140") +
                    point("2018-01-23T07:05:00+00:00", 55.7568, 37.6186, 151.0, "150.0", "3.2") +
                    point("2018-01-23T07:10:00+00:00", 55.7578, 37.6196, 152.0, "160")
            )
        )

        val workout = TcxImporter.parseTcx(xml, "run.tcx")

        assertEquals(Instant.parse("2018-01-23T07:00:00Z").toEpochMilli(), workout.date.time)
        assertEquals(600_000L, workout.duration)
        assertEquals(600_000L, workout.movingDuration)
        assertTrue(workout.distance > 0f)
        assertEquals(120, workout.calories)
        assertEquals(WorkoutType.EASY_RUN, workout.type)
        assertEquals("Imported from run.tcx", workout.notes)
        val track = TrackDataJson.parse(workout.trackData)!!
        assertEquals(listOf(140, 150, 160), track.points.map { it.heartRate })
        assertEquals(listOf(150.0, 151.0, 152.0), track.points.map { it.altitude })
        assertEquals(listOf(null, 3.2f, null), track.points.map { it.speed })
        assertEquals(ElevationSource.FILE, track.elevationSource)
        assertNull("every point has its time", track.timeSynthetic)
    }

    @Test
    fun pointsWithoutPosition_areLeftOutOfTheTrack() {
        val xml = tcx(
            lap(
                "2018-01-23T07:00:00+00:00", 600.0, 260.0, 0.0,
                point("2018-01-23T07:00:00+00:00", 55.7558, 37.6176) +
                    point("2018-01-23T07:00:01+00:00", null, null, hr = "141") +
                    point("2018-01-23T07:10:00+00:00", 55.7578, 37.6196)
            )
        )

        val track = TrackDataJson.parse(TcxImporter.parseTcx(xml).trackData)!!

        assertEquals(2, track.points.size)
        assertNull("no altitude in the file", track.elevationSource)
    }

    @Test
    fun gapTheLapsLeftOut_isAPause() {
        // 10 min running, a 15 min stop, 5 min running: the lap counts 15 min
        val xml = tcx(
            lap(
                "2018-01-01T10:00:00+00:00", 900.0, 3000.0, 0.0,
                point("2018-01-01T10:00:00+00:00", 55.7558, 37.6176) +
                    point("2018-01-01T10:10:00+00:00", 55.7700, 37.6176) +
                    point("2018-01-01T10:25:00+00:00", 55.7700, 37.6176) +
                    point("2018-01-01T10:30:00+00:00", 55.7820, 37.6176)
            )
        )

        val workout = TcxImporter.parseTcx(xml)

        assertEquals(900_000L, workout.duration)
        assertEquals(900_000L, workout.movingDuration)
        val track = TrackDataJson.parse(workout.trackData)!!
        assertEquals(900_000L, track.totalDuration)
        assertEquals(1, track.pauses!!.size)
        assertEquals(Instant.parse("2018-01-01T10:10:00Z").toEpochMilli(), track.pauses!![0].start)
        assertEquals(Instant.parse("2018-01-01T10:25:00Z").toEpochMilli(), track.pauses!![0].end)
        assertEquals(com.runner.academy.data.PauseKind.MANUAL, track.pauses!![0].kind)
    }

    @Test
    fun gapTheLapsCount_staysInTheTime() {
        val xml = tcx(
            lap(
                "2018-01-01T10:00:00+00:00", 1800.0, 3000.0, 0.0,
                point("2018-01-01T10:00:00+00:00", 55.7558, 37.6176) +
                    point("2018-01-01T10:10:00+00:00", 55.7700, 37.6176) +
                    point("2018-01-01T10:30:00+00:00", 55.7820, 37.6176)
            )
        )

        val workout = TcxImporter.parseTcx(xml)

        assertEquals(1_800_000L, workout.duration)
        assertNull(TrackDataJson.parse(workout.trackData)!!.pauses)
    }

    @Test
    fun frozenGps_takesTheLapTotalsWithoutATrack() {
        // The phone kept one place while its step counter measured 7.3 km
        val frozen = (0..9).joinToString("") { i ->
            point("2018-05-14T09:%02d:00+00:00".format(i * 4), 55.894518, 37.452345 + if (i == 9) 0.001 else 0.0, hr = "160")
        }
        val xml = tcx(lap("2018-05-14T09:00:00+00:00", 2234.0, 7300.0, 625.0, frozen))

        val workout = TcxImporter.parseTcx(xml)

        assertNull(workout.trackData)
        assertEquals(7.3f, workout.distance, 0.0001f)
        assertEquals(2_234_000L, workout.duration)
        assertEquals(625, workout.calories)
    }

    @Test
    fun treadmillRun_takesTheLapTotalsWithoutATrack() {
        val noGps = point("2018-02-13T07:00:00+00:00", null, null, hr = "150")
        val xml = tcx(
            lap("2018-02-13T07:00:00+00:00", 1200.0, 3000.0, 200.0, noGps),
            lap("2018-02-13T07:20:00+00:00", 600.5, 1500.25, 100.4, noGps)
        )

        val workout = TcxImporter.parseTcx(xml, "treadmill.tcx")

        assertNull(workout.trackData)
        assertEquals(4.50025f, workout.distance, 0.0001f)
        assertEquals(1_800_500L, workout.duration)
        assertEquals(1_800_500L, workout.movingDuration)
        assertEquals(PaceMath.avgPace(workout.distance, 1_800_500L), workout.avgPace, 0f)
        assertEquals(300, workout.calories)
        // The first lap starts it (the activity Id names the export, not necessarily the run)
        assertEquals(Instant.parse("2018-02-13T07:00:00Z").toEpochMilli(), workout.date.time)
    }

    @Test
    fun noLapStartTime_fallsBackOnTheActivityId() {
        val xml = tcx(
            """<Lap><TotalTimeSeconds>60</TotalTimeSeconds><DistanceMeters>200</DistanceMeters></Lap>"""
        )

        val workout = TcxImporter.parseTcx(xml)

        assertEquals(Instant.parse("2018-01-23T07:00:00Z").toEpochMilli(), workout.date.time)
        assertNull("no calories in the file", workout.calories)
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptyRecording_throws() {
        // Some services export runs they lost the distance of as one point with a zero lap
        val xml = tcx(lap("2018-03-13T07:00:00+00:00", 2225.0, 0.0, 441.0, point("2018-03-13T07:00:00+00:00", null, null)))
        TcxImporter.parseTcx(xml)
    }

    @Test
    fun trackFileImport_tellsTheFormatByTheContent() {
        val tcxXml = tcx(lap("2018-02-13T07:00:00+00:00", 60.0, 200.0, 0.0))
        val gpxXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
              <trk><trkseg>
                <trkpt lat="55.7558" lon="37.6176"><time>2024-01-01T10:00:00Z</time></trkpt>
                <trkpt lat="55.7568" lon="37.6186"><time>2024-01-01T10:05:00Z</time></trkpt>
              </trkseg></trk>
            </gpx>
        """.trimIndent()

        assertNull("TCX without GPS", TrackFileImport.parse(tcxXml, "a.gpx").trackData)
        assertNotNull("GPX", TrackFileImport.parse(gpxXml, "b.tcx").trackData)
    }

    @Test
    fun isTrackFileName_acceptsGpxAndTcxInAnyCase() {
        assertTrue(TrackFileImport.isTrackFileName("run.gpx"))
        assertTrue(TrackFileImport.isTrackFileName("RUN.TCX"))
        assertFalse(TrackFileImport.isTrackFileName("workouts.csv"))
        assertFalse(TrackFileImport.isTrackFileName(null))
    }

    @Test
    fun parseTime_readsZuluOffsetsAndFractions() {
        val expected = Instant.parse("2016-01-13T15:59:30Z").toEpochMilli()
        assertEquals(expected, TrackFileImport.parseTime("2016-01-13T15:59:30Z"))
        assertEquals(expected, TrackFileImport.parseTime("2016-01-13T15:59:30+00:00"))
        assertEquals(expected, TrackFileImport.parseTime("2016-01-13T18:59:30+03:00"))
        assertEquals(expected, TrackFileImport.parseTime(" 2016-01-13 15:59:30Z "))
        assertEquals(expected + 250, TrackFileImport.parseTime("2016-01-13T15:59:30.250Z"))
        assertNull(TrackFileImport.parseTime("yesterday"))
    }
}
