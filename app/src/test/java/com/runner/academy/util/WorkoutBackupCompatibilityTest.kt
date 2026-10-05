package com.runner.academy.util

import com.runner.academy.data.SegmentGoalType
import com.runner.academy.data.SegmentKind
import com.runner.academy.data.TrackData
import com.runner.academy.data.TrackPoint
import com.runner.academy.data.Workout
import com.runner.academy.data.WorkoutTemplateSegment
import com.runner.academy.data.WorkoutType
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Date

/**
 * Guards backups written by the current format (formatVersion 1, DB schema 6): every
 * later release and DB migration must still read this file without losing a field.
 * Do not edit the fixture; add a new one for a new format.
 *
 * Expectations are spelled out in full (not parsed back from the fixture), so a parser
 * that silently stops reading a field fails here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WorkoutBackupCompatibilityTest {

    private val goldenV1 = readResource("backups/workouts-v1-db6.json")

    private val expectedTrack = TrackData(
        points = listOf(
            TrackPoint(55.7558, 37.6173, 1_760_000_000_000L, 4.5f, 2.9f, 152.0, afterGap = false, source = "GPS"),
            TrackPoint(55.7560, 37.6178, 1_760_000_002_000L, 5.0f, 3.1f, 152.5, afterGap = false, source = "GPS"),
            TrackPoint(55.7590, 37.6201, 1_760_000_120_000L, 8.0f, 3.0f, null, afterGap = true, source = "GPS")
        ),
        totalDistance = 412.5f,
        totalDuration = 120_000L,
        avgSpeed = 3.44f,
        maxSpeed = 3.1f,
        startTime = 1_760_000_000_000L,
        endTime = 1_760_000_120_000L
    )

    private val expectedSegments = listOf(
        WorkoutTemplateSegment(
            templateId = 0,
            sortOrder = 0,
            kind = SegmentKind.WARMUP,
            title = "Разминка",
            goalType = SegmentGoalType.DURATION,
            durationMs = 600_000L
        ),
        WorkoutTemplateSegment(
            templateId = 0,
            sortOrder = 1,
            kind = SegmentKind.WORK,
            title = "Быстро",
            goalType = SegmentGoalType.DISTANCE,
            distanceMeters = 400f,
            targetPaceMinPerKm = 4.5f
        )
    )

    /** Workout columns; trackData / intervalSegmentsJson are compared as parsed structures. */
    private val expectedWorkouts = listOf(
        Workout(
            id = 0, // ids are reassigned on import
            date = Date(1_760_000_000_000L),
            distance = 0.4125f,
            duration = 120_000L,
            movingDuration = 120_000L,
            avgPace = 4.85f,
            calories = 31,
            notes = "Интервалы у реки",
            type = WorkoutType.INTERVAL_TRAINING,
            isFavorite = true
        ),
        Workout(
            id = 0,
            date = Date(1_760_100_000_000L),
            distance = 5.0f,
            duration = 1_500_000L,
            movingDuration = 1_500_000L,
            avgPace = 5.0f,
            calories = null,
            notes = null,
            type = WorkoutType.RACE,
            isFavorite = false
        )
    )

    @Test
    fun goldenV1_restoresEveryWorkoutField() {
        assertWorkoutsMatchGolden(WorkoutBackupFormat.parseBackupJson(goldenV1))
    }

    @Test
    fun currentExport_reImportsGoldenV1Losslessly() {
        val reExported = WorkoutBackupFormat.toBackupJson(
            WorkoutBackupFormat.parseBackupJson(goldenV1),
            "com.runner.academy"
        )
        assertWorkoutsMatchGolden(WorkoutBackupFormat.parseBackupJson(reExported))
    }

    private fun assertWorkoutsMatchGolden(restored: List<Workout>) {
        assertEquals(
            expectedWorkouts,
            restored.map { it.copy(trackData = null, intervalSegmentsJson = null) }
        )
        assertEquals(expectedTrack, TrackDataJson.parse(restored[0].trackData))
        assertEquals(expectedSegments, IntervalSegmentsJson.parse(restored[0].intervalSegmentsJson))
        assertEquals(null, restored[1].trackData)
        assertEquals(null, restored[1].intervalSegmentsJson)
    }

    private fun readResource(path: String): String =
        requireNotNull(javaClass.classLoader?.getResource(path)) { "missing test resource $path" }
            .readText()
}
