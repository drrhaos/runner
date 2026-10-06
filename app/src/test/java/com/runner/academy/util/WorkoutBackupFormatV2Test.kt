package com.runner.academy.util

import com.google.gson.JsonParser
import com.runner.academy.data.ElevationSource
import com.runner.academy.data.Workout
import com.runner.academy.data.WorkoutType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Modifier
import java.util.Date

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WorkoutBackupFormatV2Test {

    /** Workout property → its key in a format 2 backup. */
    private val exportedKeys = mapOf(
        "id" to "id",
        "date" to "dateMillis",
        "distance" to "distanceKm",
        "duration" to "durationMs",
        "movingDuration" to "movingDurationMs",
        "avgPace" to "avgPace",
        "calories" to "calories",
        "notes" to "notes",
        "type" to "type",
        "trackData" to "trackData",
        "isFavorite" to "isFavorite",
        "intervalSegmentsJson" to "intervalSegmentsJson",
        "excludeFromRecords" to "excludeFromRecords",
        "avgHeartRate" to "avgHeartRate",
        "maxHeartRate" to "maxHeartRate"
    )

    /** Recomputed from the track after import instead of trusting the file. */
    private val derived = setOf(
        "elevationGain",
        "elevationLoss",
        "elevationSource",
        "avgCadence",
        "routePreview",
        "metricsVersion"
    )

    private val full = Workout(
        id = 5,
        date = Date(1_790_000_000_000L),
        distance = 10f,
        duration = 3_600_000L,
        movingDuration = 3_000_000L,
        avgPace = 5f,
        calories = 600,
        notes = "n",
        type = WorkoutType.LONG_RUN,
        trackData = "{\"points\":[],\"total_distance\":10000.0,\"total_duration\":3600000}",
        isFavorite = true,
        intervalSegmentsJson = "[]",
        elevationGain = 120f,
        elevationLoss = 118f,
        elevationSource = ElevationSource.GPS,
        avgCadence = 170f,
        routePreview = "1|2|S:0,0",
        excludeFromRecords = true,
        avgHeartRate = 150,
        maxHeartRate = 182,
        metricsVersion = 3
    )

    @Test
    fun everyWorkoutField_isExportedOrListedAsDerived() {
        val fields = Workout::class.java.declaredFields
            .filter { !Modifier.isStatic(it.modifiers) && !it.isSynthetic }
            .map { it.name }
            .toSet()

        assertEquals(
            "a new Workout column must be exported in the backup or listed as derived",
            fields,
            exportedKeys.keys + derived
        )
    }

    @Test
    fun export_writesEveryExportedKey_andNoDerivedOne() {
        val workout = JsonParser.parseString(WorkoutBackupFormat.toBackupJson(listOf(full), "p"))
            .asJsonObject.getAsJsonArray("workouts")[0].asJsonObject

        exportedKeys.values.forEach { key -> assertTrue("missing $key", workout.has(key)) }
        derived.forEach { key -> assertFalse("derived $key exported", workout.has(key)) }
        assertEquals(3_000_000L, workout.get("movingDurationMs").asLong)
    }

    @Test
    fun roundTrip_keepsExportedFields_resetsDerived() {
        val restored = WorkoutBackupFormat.parseBackupJson(WorkoutBackupFormat.toBackupJson(listOf(full), "p"))
            .single()

        assertEquals(
            full.copy(
                id = 0,
                elevationGain = null,
                elevationLoss = null,
                elevationSource = null,
                avgCadence = null,
                routePreview = null,
                metricsVersion = 0
            ),
            restored.copy(trackData = full.trackData)
        )
    }

    @Test
    fun format2_movingTimeOutOfRange_fallsBackToDuration() {
        listOf("0", "-5", "1900000", "\"x\"").forEach { moving ->
            val restored = WorkoutBackupFormat.parseBackupJson(backup(2, "\"movingDurationMs\": $moving,")).single()
            assertEquals("moving $moving", 1_800_000L, restored.movingDuration)
            assertEquals(PaceMath.avgPace(5f, 1_800_000L), restored.avgPace, 0f)
        }
    }

    @Test
    fun format2_validMovingTime_drivesPace() {
        val restored = WorkoutBackupFormat.parseBackupJson(backup(2, "\"movingDurationMs\": 1500000,")).single()

        assertEquals(1_800_000L, restored.duration)
        assertEquals(1_500_000L, restored.movingDuration)
        assertEquals(5f, restored.avgPace, 0.0001f)
    }

    @Test
    fun movingTimeMissingOrOutOfRange_fallsBackToDurationMinusAutoPauses() {
        listOf("", "\"movingDurationMs\": 0,", "\"movingDurationMs\": 1900000,").forEach { moving ->
            val restored = WorkoutBackupFormat.parseBackupJson(backup(2, moving + trackWithPauses)).single()

            assertEquals("moving '$moving'", 1_800_000L - 300_000L, restored.movingDuration)
            assertEquals(PaceMath.avgPace(5f, 1_500_000L), restored.avgPace, 0f)
        }
    }

    @Test
    fun validMovingTime_winsOverThePauses() {
        val restored = WorkoutBackupFormat.parseBackupJson(
            backup(2, "\"movingDurationMs\": 1700000,$trackWithPauses")
        ).single()

        assertEquals(1_700_000L, restored.movingDuration)
    }

    @Test
    fun autoPausesNotBelowDuration_fallBackToDuration() {
        val track = """"trackData": {"points": [], "total_distance": 5000.0, "total_duration": 1800000,
            "start_time": 0, "pauses": [{"start": 0, "end": 1800000, "kind": "AUTO"}]},"""

        val restored = WorkoutBackupFormat.parseBackupJson(backup(2, track)).single()

        assertEquals(1_800_000L, restored.movingDuration)
    }

    /** 300 s of auto-pause and 60 s of manual pause (not subtracted: outside duration already). */
    private val trackWithPauses = """
        "trackData": {"points": [], "total_distance": 5000.0, "total_duration": 1800000, "start_time": 0,
          "pauses": [{"start": 100000, "end": 160000, "kind": "MANUAL"},
                     {"start": 200000, "end": 400000, "kind": "AUTO"},
                     {"start": 500000, "end": 600000, "kind": "AUTO"}]},
    """.trimIndent()

    @Test
    fun format1_movingTimeIsDuration_flagsDefault() {
        val restored = WorkoutBackupFormat.parseBackupJson(backup(1, "")).single()

        assertEquals(1_800_000L, restored.movingDuration)
        assertEquals(false, restored.excludeFromRecords)
        assertEquals(null, restored.avgHeartRate)
        assertEquals(0, restored.metricsVersion)
    }

    private fun backup(formatVersion: Int, extra: String) = """
        {
          "formatVersion": $formatVersion,
          "workouts": [
            {
              "dateMillis": 1790000000000,
              "distanceKm": 5.0,
              "durationMs": 1800000,
              $extra
              "avgPace": 9.9,
              "type": "EASY_RUN"
            }
          ]
        }
    """.trimIndent()
}
