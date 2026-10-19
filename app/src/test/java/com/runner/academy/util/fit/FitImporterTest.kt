package com.runner.academy.util.fit

import com.runner.academy.data.ElevationSource
import com.runner.academy.data.PauseKind
import com.runner.academy.data.WorkoutType
import com.runner.academy.util.ActivitySport
import com.runner.academy.util.LapsJson
import com.runner.academy.util.NotRunningException
import com.runner.academy.util.TrackDataJson
import com.runner.academy.util.TrackFileImport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class FitImporterTest {

    private val start = 1_700_000_000_000L

    /** Running north from 55°N 37°E, a point every 10 s, about 33 m apart. */
    private fun FitTestWriter.track(points: Int = 7, from: Long = start, heartRate: Int? = 150): FitTestWriter {
        for (i in 0 until points) {
            record(
                time = from + i * 10_000L,
                lat = 55.0 + i * 0.0003,
                lon = 37.0,
                enhancedAltitude = 120.0 + i,
                heartRate = heartRate,
                cadence = 85,
                fractionalCadence = 64,
                distanceM = i * 33.4
            )
        }
        return this
    }

    private fun workout(writer: FitTestWriter, fileName: String? = "run.fit") =
        FitImporter.parseFit(writer.bytes().inputStream(), fileName)

    private fun activity(bytes: ByteArray) = FitImporter.parse(bytes.inputStream())

    @Test
    fun `a GPS run keeps its track, the totals are the watch's`() {
        val writer = FitTestWriter()
            .fileId()
            .timer(start, type = 0)
            .track()
            .timer(start + 60_000, type = 4)
            .session(start, timerS = 61.5, distanceM = 205.0, calories = 21, avgHr = 150, maxHr = 160, avgCadence = 85)

        val workout = workout(writer)

        assertEquals(start, workout.date.time)
        assertEquals(0.205f, workout.distance, 1e-4f)
        assertEquals(61_500L, workout.duration)
        assertEquals(21, workout.calories)
        assertEquals(WorkoutType.EASY_RUN, workout.type)
        assertEquals("Imported from run.fit", workout.notes)
        assertNull(workout.title)
        val track = TrackDataJson.parse(workout.trackData)
        assertNotNull(track)
        assertEquals(7, track!!.points.size)
        val point = track.points[2]
        assertEquals(55.0006, point.latitude, 1e-6)
        assertEquals(37.0, point.longitude, 1e-6)
        assertEquals(start + 20_000, point.timestamp)
        assertEquals(122.0, point.altitude!!, 0.01)
        assertEquals(150, point.heartRate)
        // 85.5 strides of one foot a minute: 171 steps
        assertEquals(171f, point.cadence!!, 0.01f)
        assertEquals(ElevationSource.FILE, track.elevationSource)
    }

    @Test
    fun `the enhanced altitude and speed win over the plain ones`() {
        val writer = FitTestWriter()
            .fileId()
            .record(start, 55.0, 37.0, altitude = 10.0, enhancedAltitude = 20.0, speed = 1.0, enhancedSpeed = 3.0)
            .record(start + 1000, 55.0001, 37.0, altitude = 11.0)

        val points = activity(writer.bytes()).points

        assertEquals(20.0, points[0].altitude!!, 0.01)
        assertEquals(3.0f, points[0].speed!!, 0.001f)
        assertEquals(11.0, points[1].altitude!!, 0.01)
    }

    @Test
    fun `points without a position are not on the track`() {
        val writer = FitTestWriter()
            .fileId()
            // GPS not yet fixed, then "null island"
            .record(start, distanceM = 0.0, heartRate = 120)
            .record(start + 1000, 0.0, 0.0)
            .record(start + 2000, 55.0, 37.0)
            .record(start + 3000, 55.0001, 37.0)

        val points = activity(writer.bytes()).points

        assertEquals(listOf(start + 2000, start + 3000), points.map { it.timestamp })
    }

    @Test
    fun `points and laps follow time, not the order in the file`() {
        val writer = FitTestWriter()
            .fileId()
            // Summary first: the laps before the points, the second lap first
            .lap(start = start + 30_000, timerS = 30.0, distanceM = 100.0)
            .lap(start = start, timerS = 30.0, distanceM = 100.0)
            .record(start + 2000, 55.0002, 37.0)
            .record(start, 55.0, 37.0)
            .record(start + 1000, 55.0001, 37.0)

        val parsed = activity(writer.bytes())

        assertEquals(listOf(start, start + 1000, start + 2000), parsed.points.map { it.timestamp })
        assertEquals(listOf(start, start + 30_000), parsed.laps.map { it.start })
    }

    @Test
    fun `a service is no device`() {
        assertNull(activity(FitTestWriter().fileId(manufacturer = 265).track(points = 2).bytes()).device)
    }

    @Test
    fun `timer events become pauses, automatic or by hand`() {
        val writer = FitTestWriter()
            .fileId()
            .timer(start, type = 0)
            .track(points = 3)
            .timer(start + 20_000, type = 1, trigger = 1)
            .timer(start + 50_000, type = 0, trigger = 1)
            .track(points = 3, from = start + 50_000)
            .timer(start + 70_000, type = 4, trigger = 0)
            .timer(start + 90_000, type = 0, trigger = 0)
            .track(points = 2, from = start + 90_000)
            .timer(start + 100_000, type = 4)

        val pauses = activity(writer.bytes()).pauses!!

        assertEquals(2, pauses.size)
        assertEquals(start + 20_000, pauses[0].start)
        assertEquals(start + 50_000, pauses[0].end)
        assertEquals(PauseKind.AUTO, pauses[0].kind)
        assertEquals(start + 70_000, pauses[1].start)
        assertEquals(start + 90_000, pauses[1].end)
        assertEquals(PauseKind.MANUAL, pauses[1].kind)
    }

    @Test
    fun `without timer events there are no pauses, gaps or not`() {
        val writer = FitTestWriter()
            .fileId()
            .track(points = 3)
            .track(points = 3, from = start + 600_000)

        assertNull(activity(writer.bytes()).pauses)
    }

    @Test
    fun `without a session the totals come from the records`() {
        val writer = FitTestWriter()
            .fileId()
            .timer(start, type = 0)
            .track(points = 4)
            .timer(start + 30_000, type = 1)
            .timer(start + 90_000, type = 0)
            .track(points = 4, from = start + 90_000)

        val parsed = activity(writer.bytes())

        // The last record distance: 3 × 33.4 m
        assertEquals(100.2, parsed.totals!!.distanceM!!, 0.01)
        // 120 s from the first to the last record, minus the minute paused
        assertEquals(60_000L, parsed.totals!!.timerMs)
        assertEquals(start, parsed.startTime)
    }

    @Test
    fun `a treadmill run has no track and keeps the session's heart rate and cadence`() {
        val writer = FitTestWriter()
            .fileId()
            .sport(1, 1, "Treadmill")
            .record(start, distanceM = 0.0, heartRate = 140)
            .record(start + 600_000, distanceM = 1990.0, heartRate = 150)
            .session(start, subSport = 1, timerS = 600.0, distanceM = 2000.0, avgHr = 145, maxHr = 158, avgCadence = 82)

        val workout = workout(writer)

        assertNull(workout.trackData)
        assertEquals(2.0f, workout.distance, 1e-4f)
        assertEquals(600_000L, workout.duration)
        assertEquals(145, workout.avgHeartRate)
        assertEquals(158, workout.maxHeartRate)
        assertEquals(164f, workout.avgCadence!!, 0.01f)
    }

    @Test
    fun `laps keep their start, time, distance, heart rate and steps`() {
        val writer = FitTestWriter()
            .fileId()
            .track()
            .lap(start = start, end = start + 30_000, timerS = 30.0, distanceM = 100.0, avgHr = 140, maxHr = 150, avgCadence = 80)
            // No start time: its end minus the elapsed time
            .lap(start = null, end = start + 60_000, timerS = 28.0, elapsedS = 30.0, distanceM = 105.0)
            .session(start, timerS = 58.0, distanceM = 205.0)

        val workout = workout(writer)
        val laps = LapsJson.parse(workout.lapsJson)

        assertEquals(2, laps.size)
        assertEquals(start, laps[0].start)
        assertEquals(30_000L, laps[0].timerMs)
        assertEquals(100f, laps[0].distanceM!!, 0.01f)
        assertEquals(140, laps[0].avgHr)
        assertEquals(150, laps[0].maxHr)
        assertEquals(160f, laps[0].avgCadence!!, 0.01f)
        assertEquals(start + 30_000, laps[1].start)
        assertEquals(28_000L, laps[1].timerMs)
        assertNull(laps[1].avgHr)
    }

    @Test
    fun `the device is the product name, else the maker`() {
        fun device(writer: FitTestWriter) = activity(writer.track(points = 2).bytes()).device

        assertEquals("Run Watch 265", device(FitTestWriter().fileId(productName = "Run Watch 265")))
        assertEquals(
            "Trail Watch 3",
            device(
                FitTestWriter().fileId(manufacturer = 294)
                    .deviceInfo(1, manufacturer = 1, productName = "Chest Strap")
                    .deviceInfo(0, manufacturer = 294, productName = "Trail Watch 3")
            )
        )
        assertEquals("Maker A", device(FitTestWriter().fileId(manufacturer = 1)))
        assertEquals("Maker F", device(FitTestWriter().fileId(manufacturer = 294)))
        assertNull(device(FitTestWriter().fileId(manufacturer = 9999)))
    }

    @Test
    fun `the sport of the sessions decides`() {
        fun sport(writer: FitTestWriter) = activity(writer.bytes()).sport
        fun file() = FitTestWriter().fileId().track(points = 2)

        assertEquals(ActivitySport.RUNNING, sport(file().session(start, sport = 1, subSport = 3)))
        assertEquals(ActivitySport.UNKNOWN, sport(file().session(start, sport = 0)))
        assertEquals(ActivitySport.OTHER, sport(file().session(start, sport = 11)))
        // No session: the sport message, then the laps would tell
        assertEquals(ActivitySport.OTHER, sport(file().sport(2)))
        assertEquals(ActivitySport.UNKNOWN, sport(file()))
    }

    @Test(expected = NotRunningException::class)
    fun `a walk is not a run`() {
        workout(FitTestWriter().fileId().track().session(start, sport = 11, timerS = 60.0, distanceM = 200.0))
    }

    @Test(expected = NotRunningException::class)
    fun `a multisport file is no run as a whole`() {
        workout(
            FitTestWriter().fileId().track()
                .session(start, sport = 1, timerS = 30.0, distanceM = 100.0)
                .session(start + 30_000, sport = 2, timerS = 30.0, distanceM = 400.0)
        )
    }

    @Test(expected = NotRunningException::class)
    fun `an automatic multisport activity is no run`() {
        workout(FitTestWriter().fileId().track().activity(type = 1).session(start, timerS = 60.0, distanceM = 200.0))
    }

    @Test
    fun `several running sessions are one workout`() {
        val writer = FitTestWriter().fileId().track()
            .session(start, timerS = 30.0, distanceM = 100.0, calories = 10, maxHr = 150)
            .session(start + 30_000, timerS = 31.0, distanceM = 105.0, calories = 11, maxHr = 155)

        val workout = workout(writer)

        assertEquals(0.205f, workout.distance, 1e-4f)
        assertEquals(61_000L, workout.duration)
        assertEquals(21, workout.calories)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a course file is not an activity`() {
        workout(FitTestWriter().fileId(type = 6).track())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an activity with neither records nor a session is an error`() {
        workout(FitTestWriter().fileId().sport(1))
    }

    @Test
    fun `a cut file imports what was read`() {
        val bytes = FitTestWriter().fileId().track().bytes()

        val workout = FitImporter.parseFit(bytes.copyOf(bytes.size - 5).inputStream(), "cut.fit")

        assertTrue(TrackDataJson.parse(workout.trackData)!!.points.size >= 6)
    }

    @Test
    fun `a file with a bad CRC still imports`() {
        val bytes = FitTestWriter().fileId().track().session(start, timerS = 60.0, distanceM = 200.0).bytes(badCrc = true)

        val workout = FitImporter.parseFit(bytes.inputStream(), "bad.fit")

        assertEquals(0.2f, workout.distance, 1e-4f)
    }

    @Test
    fun `a track file import reads FIT by its content`() {
        val bytes = FitTestWriter().fileId().track().session(start, timerS = 60.0, distanceM = 200.0).bytes()

        val workout = TrackFileImport.parse(bytes.inputStream(), "export.bin")

        assertEquals(0.2f, workout.distance, 1e-4f)
        assertTrue(TrackFileImport.isTrackFileName("Run.FIT"))
    }
}
