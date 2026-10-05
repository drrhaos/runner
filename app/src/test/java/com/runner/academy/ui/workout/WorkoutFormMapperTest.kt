package com.runner.academy.ui.workout

import com.runner.academy.data.ElevationSource
import com.runner.academy.data.Workout
import com.runner.academy.data.WorkoutType
import com.runner.academy.util.PaceMath
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Date

class WorkoutFormMapperTest {

    private val original = Workout(
        id = 7,
        date = Date(1_700_000_000_000L),
        distance = 10f,
        duration = 3_600_400L, // recorded: not whole seconds
        movingDuration = 3_300_000L, // 5 min of auto-pause
        avgPace = 5.5f,
        calories = 600,
        notes = "old",
        type = WorkoutType.LONG_RUN,
        trackData = "{\"points\":[]}",
        isFavorite = true,
        intervalSegmentsJson = "[]",
        elevationGain = 120f,
        elevationLoss = 118f,
        elevationSource = ElevationSource.GPS,
        avgCadence = 168f,
        routePreview = "1|2|S:0,0",
        excludeFromRecords = true,
        avgHeartRate = 150,
        maxHeartRate = 182,
        metricsVersion = 1
    )

    private fun form(
        durationMs: Long = 3_600_000L, // the form shows whole seconds
        distanceKm: Float = 10f,
        date: Date = Date(1_700_000_000_000L),
        trackDataJson: String? = "{\"points\":[]}"
    ) = WorkoutFormMapper.FormInput(
        date = date,
        type = WorkoutType.TEMPO_RUN,
        distanceKm = distanceKm,
        durationMs = durationMs,
        calories = 610,
        notes = "new",
        trackDataJson = trackDataJson
    )

    @Test
    fun newWorkout_movingDurationIsDuration() {
        val workout = WorkoutFormMapper.toWorkout(form(durationMs = 1_800_000L, distanceKm = 5f), null)

        assertEquals(0L, workout.id)
        assertEquals(1_800_000L, workout.duration)
        assertEquals(1_800_000L, workout.movingDuration)
        assertEquals(6f, workout.avgPace, 0.0001f)
        assertEquals(WorkoutType.TEMPO_RUN, workout.type)
        assertEquals(610, workout.calories)
        assertEquals("new", workout.notes)
        assertEquals(false, workout.excludeFromRecords)
        assertEquals(0, workout.metricsVersion)
    }

    @Test
    fun edit_timeUnchanged_keepsMovingDurationAndEveryOtherField() {
        val workout = WorkoutFormMapper.toWorkout(form(), original)

        // The form's whole seconds equal the original time: the exact recorded time stays
        assertEquals(original.duration, workout.duration)
        assertEquals(original.movingDuration, workout.movingDuration)
        assertEquals(PaceMath.avgPace(10f, original.movingDuration), workout.avgPace, 0f)
        assertEquals(
            original.copy(
                type = WorkoutType.TEMPO_RUN,
                calories = 610,
                notes = "new",
                avgPace = workout.avgPace
            ),
            workout
        )
    }

    @Test
    fun edit_newTime_movingDurationIsNewTime_paceRecomputed() {
        val workout = WorkoutFormMapper.toWorkout(form(durationMs = 4_200_000L), original)

        assertEquals(4_200_000L, workout.duration)
        assertEquals(4_200_000L, workout.movingDuration)
        assertEquals(7f, workout.avgPace, 0.0001f)
        assertEquals(true, workout.excludeFromRecords)
        assertEquals(true, workout.isFavorite)
        assertEquals(150, workout.avgHeartRate)
        assertEquals(original.id, workout.id)
    }

    @Test
    fun edit_newDistance_paceFromKeptMovingDuration() {
        val workout = WorkoutFormMapper.toWorkout(form(distanceKm = 11f), original)

        assertEquals(11f, workout.distance, 0f)
        assertEquals(PaceMath.avgPace(11f, 3_300_000L), workout.avgPace, 0f)
    }

    @Test
    fun edit_carriesDateAndChosenRoute() {
        val workout = WorkoutFormMapper.toWorkout(
            form(date = Date(1_800_000_000_000L), trackDataJson = null),
            original
        )

        assertEquals(Date(1_800_000_000_000L), workout.date)
        assertEquals(null, workout.trackData)
        assertEquals("[]", workout.intervalSegmentsJson)
    }
}
