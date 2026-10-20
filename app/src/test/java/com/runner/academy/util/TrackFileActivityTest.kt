package com.runner.academy.util

import com.runner.academy.data.Lap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/** What a GPX or TCX file gives a workout beyond its track: sport, title, notes, device, laps, totals. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TrackFileActivityTest {

    private fun gpx(trk: String = "", metadata: String = "") = """
        <?xml version="1.0" encoding="UTF-8"?>
        <gpx version="1.1" creator="Some Service" xmlns="http://www.topografix.com/GPX/1/1">
          <metadata>$metadata</metadata>
          <trk>$trk<trkseg>
            <trkpt lat="55.7558" lon="37.6176"><name>point</name><time>2024-01-01T10:00:00Z</time></trkpt>
            <trkpt lat="55.7608" lon="37.6176"><time>2024-01-01T10:05:00Z</time></trkpt>
            <trkpt lat="55.7658" lon="37.6176"><time>2024-01-01T10:10:00Z</time></trkpt>
          </trkseg></trk>
        </gpx>
    """.trim()

    private fun tcx(sport: String = "Running", activity: String = "", laps: String) = """
        <?xml version="1.0" encoding="UTF-8"?>
        <TrainingCenterDatabase xmlns="http://www.garmin.com/xmlschemas/TrainingCenterDatabase/v2"
            xmlns:ns3="http://www.garmin.com/xmlschemas/ActivityExtension/v2">
          <Activities><Activity Sport="$sport">
            <Id>2024-01-01T10:00:00Z</Id>
            $laps
            $activity
          </Activity></Activities>
          <Author><Name>Connect Api</Name></Author>
        </TrainingCenterDatabase>
    """.trim()

    private fun lap(start: String, seconds: Int, meters: Int, avgHr: Int?, maxHr: Int?, cadence: Int?, track: String = "") = """
        <Lap StartTime="$start">
          <TotalTimeSeconds>$seconds</TotalTimeSeconds>
          <DistanceMeters>$meters</DistanceMeters>
          ${avgHr?.let { "<AverageHeartRateBpm><Value>$it</Value></AverageHeartRateBpm>" } ?: ""}
          ${maxHr?.let { "<MaximumHeartRateBpm><Value>$it</Value></MaximumHeartRateBpm>" } ?: ""}
          <Notes>lap note</Notes>
          <Track>$track</Track>
          ${cadence?.let { "<Extensions><ns3:LX><ns3:AvgRunCadence>$it</ns3:AvgRunCadence></ns3:LX></Extensions>" } ?: ""}
        </Lap>
    """

    private fun point(time: String, lat: Double, lon: Double) = """
        <Trackpoint><Time>$time</Time>
          <Position><LatitudeDegrees>$lat</LatitudeDegrees><LongitudeDegrees>$lon</LongitudeDegrees></Position>
        </Trackpoint>
    """

    private fun ms(text: String) = Instant.parse(text).toEpochMilli()

    @Test
    fun gpx_trackNameAndDescription_becomeTitleAndNotes() {
        val workout = TrackFileImport.parse(
            gpx(trk = "<name>Morning Run</name><desc>Easy along the river</desc><type>9</type>"),
            "a.gpx"
        )

        assertEquals("Morning Run", workout.title)
        assertEquals("Easy along the river", workout.notes)
        assertNull("a GPX creator is a service, not a device", workout.device)
        assertNull(workout.lapsJson)
    }

    @Test
    fun gpx_metadataName_isTheTitleWithoutATrackName_andNoDescriptionKeepsTheImportNote() {
        val workout = TrackFileImport.parse(gpx(metadata = "<name>Lunch Run</name>"), "a.gpx")

        assertEquals("Lunch Run", workout.title)
        assertEquals("Imported from a.gpx", workout.notes)
    }

    @Test
    fun gpx_otherSports_areNoRun() {
        for (type in listOf("1", "4", "10", "Ride", "walking", "Hiking", "swimming", "VirtualRide")) {
            assertThrows(type, NotRunningException::class.java) {
                TrackFileImport.parse(gpx(trk = "<type>$type</type>"), "a.gpx")
            }
        }
    }

    @Test
    fun gpx_runsAndUnknownTypes_areImported() {
        for (type in listOf("9", "running", "Run", "trail_running", "TrailRun", "treadmill_running", "virtual_run", "42", "parkour", "")) {
            assertNotNull(type, TrackFileImport.parse(gpx(trk = "<type>$type</type>"), "a.gpx"))
        }
        assertNotNull(TrackFileImport.parse(gpx(), "a.gpx"))
    }

    @Test
    fun tcx_biking_isNoRun_otherIsARun() {
        val laps = lap("2024-01-01T10:00:00Z", 600, 2000, null, null, null)

        assertThrows(NotRunningException::class.java) { TrackFileImport.parse(tcx(sport = "Biking", laps = laps), "a.tcx") }
        assertNotNull(TrackFileImport.parse(tcx(sport = "Other", laps = laps), "a.tcx"))
    }

    @Test
    fun tcx_creatorIsTheDevice_activityNotesTheNotes() {
        val workout = TrackFileImport.parse(
            tcx(
                activity = "<Notes>Tempo with friends</Notes><Creator><Name>Run Watch 255</Name></Creator>",
                laps = lap("2024-01-01T10:00:00Z", 600, 2000, null, null, null)
            ),
            "a.tcx"
        )

        assertEquals("Run Watch 255", workout.device)
        assertEquals("Tempo with friends", workout.notes)
        assertNull(workout.title)
    }

    @Test
    fun tcx_laps_areKept_withRunCadenceInSteps() {
        val workout = TrackFileImport.parse(
            tcx(
                laps = lap("2024-01-01T10:00:00Z", 300, 1000, 150, 160, 85) +
                    lap("2024-01-01T10:05:00Z", 330, 1000, null, null, null)
            ),
            "a.tcx"
        )

        assertEquals(
            listOf(
                Lap(ms("2024-01-01T10:00:00Z"), 300_000L, 1000f, 150, 160, 170f),
                Lap(ms("2024-01-01T10:05:00Z"), 330_000L, 1000f, null, null, null)
            ),
            LapsJson.parse(workout.lapsJson)
        )
    }

    @Test
    fun tcx_withATrack_takesDistanceAndTimeFromTheLaps() {
        // The points span 10 min over about 1.1 km; the laps say 9 min 30 s and 1.2 km
        val track = point("2024-01-01T10:00:00Z", 55.7558, 37.6176) +
            point("2024-01-01T10:05:00Z", 55.7608, 37.6176) +
            point("2024-01-01T10:10:00Z", 55.7658, 37.6176)
        val workout = TrackFileImport.parse(tcx(laps = lap("2024-01-01T10:00:00Z", 570, 1200, null, null, null, track)), "a.tcx")

        assertNotNull(workout.trackData)
        assertEquals(1.2f, workout.distance, 0.0001f)
        assertEquals(570_000L, workout.duration)
        assertEquals(570_000L, workout.movingDuration)
        assertEquals(PaceMath.avgPace(1.2f, 570_000L), workout.avgPace, 0.0001f)
    }

    @Test
    fun tcx_withoutATrack_takesHeartRateAndCadenceFromTheLaps() {
        val workout = TrackFileImport.parse(
            tcx(
                laps = lap("2024-01-01T10:00:00Z", 300, 1000, 140, 150, 80) +
                    lap("2024-01-01T10:05:00Z", 600, 2000, 170, 182, 86)
            ),
            "a.tcx"
        )

        assertNull(workout.trackData)
        // Over the time of each lap
        assertEquals(160, workout.avgHeartRate)
        assertEquals(182, workout.maxHeartRate)
        assertEquals(168f, workout.avgCadence!!, 0.001f)
        assertEquals(3f, workout.distance, 0.0001f)
        assertEquals(900_000L, workout.duration)
    }
}
