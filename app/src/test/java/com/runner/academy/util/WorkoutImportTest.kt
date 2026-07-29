package com.runner.academy.util

import com.runner.academy.data.WorkoutType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WorkoutImportTest {

    @Test
    fun parseBackupJson_restoresWorkoutsWithTrackData() {
        val json = """
            {
              "formatVersion": 1,
              "exportedAt": "2026-07-29T12:00:00Z",
              "packageName": "com.example.runner",
              "workoutCount": 1,
              "workouts": [
                {
                  "id": 42,
                  "dateMillis": 1700000000000,
                  "distanceKm": 5.2,
                  "durationMs": 1800000,
                  "avgPace": 5.77,
                  "calories": 320,
                  "notes": "morning",
                  "type": "TEMPO_RUN",
                  "trackData": "{\"points\":[],\"total_distance\":5200,\"total_duration\":1800000,\"avg_speed\":2.8,\"max_speed\":4.0,\"start_time\":1700000000000,\"end_time\":1700001800000}"
                }
              ]
            }
        """.trimIndent()

        val workouts = WorkoutBackupFormat.parseBackupJson(json)
        assertEquals(1, workouts.size)
        val workout = workouts[0]
        assertEquals(0L, workout.id)
        assertEquals(5.2f, workout.distance, 0.001f)
        assertEquals(1_800_000L, workout.duration)
        assertEquals(WorkoutType.TEMPO_RUN, workout.type)
        assertEquals("morning", workout.notes)
        assertNotNull(workout.trackData)
        assertTrue(workout.trackData!!.contains("total_distance"))
    }

    @Test
    fun parseBackupJson_unknownTypeDefaultsToEasyRun() {
        val json = """
            {
              "formatVersion": 1,
              "workoutCount": 1,
              "workouts": [
                {
                  "dateMillis": 1700000000000,
                  "distanceKm": 1.0,
                  "durationMs": 600000,
                  "avgPace": 10.0,
                  "type": "UNKNOWN_TYPE"
                }
              ]
            }
        """.trimIndent()

        val workouts = WorkoutBackupFormat.parseBackupJson(json)
        assertEquals(WorkoutType.EASY_RUN, workouts[0].type)
    }

    @Test(expected = IllegalArgumentException::class)
    fun parseBackupJson_emptyWorkoutsThrows() {
        WorkoutBackupFormat.parseBackupJson(
            """{"formatVersion":1,"workouts":[]}"""
        )
    }

    @Test
    fun parseGpx_buildsWorkoutFromTrackPoints() {
        val gpx = """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
              <trk>
                <name>Test run</name>
                <trkseg>
                  <trkpt lat="55.755800" lon="37.617600">
                    <ele>150.0</ele>
                    <time>2024-01-01T10:00:00Z</time>
                  </trkpt>
                  <trkpt lat="55.756800" lon="37.618600">
                    <ele>151.0</ele>
                    <time>2024-01-01T10:05:00Z</time>
                  </trkpt>
                  <trkpt lat="55.757800" lon="37.619600">
                    <ele>152.0</ele>
                    <time>2024-01-01T10:10:00Z</time>
                  </trkpt>
                </trkseg>
              </trk>
            </gpx>
        """.trimIndent()

        val workout = GpxImporter.parseGpx(gpx, "test.gpx")
        assertEquals(0L, workout.id)
        assertEquals(WorkoutType.EASY_RUN, workout.type)
        assertTrue(workout.distance > 0f)
        assertEquals(600_000L, workout.duration)
        assertNotNull(workout.trackData)
        assertEquals("Imported from test.gpx", workout.notes)
    }

    @Test(expected = IllegalArgumentException::class)
    fun parseGpx_tooFewPointsThrows() {
        val gpx = """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
              <trk><trkseg>
                <trkpt lat="55.7558" lon="37.6176"><time>2024-01-01T10:00:00Z</time></trkpt>
              </trkseg></trk>
            </gpx>
        """.trimIndent()
        GpxImporter.parseGpx(gpx)
    }
}
