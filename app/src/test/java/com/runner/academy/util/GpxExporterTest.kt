package com.runner.academy.util

import androidx.test.core.app.ApplicationProvider
import com.runner.academy.data.TrackData
import com.runner.academy.data.TrackPoint
import com.runner.academy.data.Workout
import com.runner.academy.data.WorkoutType
import org.junit.Assert.assertEquals
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
}
