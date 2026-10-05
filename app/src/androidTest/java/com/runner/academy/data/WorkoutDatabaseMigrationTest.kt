package com.runner.academy.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class WorkoutDatabaseMigrationTest {

    private val testDb = "migration-test"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        WorkoutDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    @Throws(IOException::class)
    fun migrate1To2_addsTrackDataColumn() {
        helper.createDatabase(testDb, 1).apply {
            execSQL(
                """
                INSERT INTO workouts (date, distance, duration, avgPace, calories, notes, type)
                VALUES (1700000000000, 5.0, 1800000, 6.0, 350, NULL, 'EASY_RUN')
                """.trimIndent()
            )
            close()
        }

        helper.runMigrationsAndValidate(
            testDb,
            2,
            true,
            WorkoutDatabase.MIGRATION_1_2
        ).apply {
            query("PRAGMA table_info(workouts)").use { cursor ->
                var hasTrackData = false
                while (cursor.moveToNext()) {
                    if (cursor.getString(cursor.getColumnIndexOrThrow("name")) == "trackData") {
                        hasTrackData = true
                        break
                    }
                }
                assertTrue(hasTrackData)
            }
            close()
        }
    }

    @Test
    @Throws(IOException::class)
    fun migrate2To3_addsIsFavoriteColumn() {
        helper.createDatabase(testDb, 2).apply {
            execSQL(
                """
                INSERT INTO workouts (date, distance, duration, avgPace, calories, notes, type, trackData)
                VALUES (1700000000000, 5.0, 1800000, 6.0, 350, NULL, 'EASY_RUN', NULL)
                """.trimIndent()
            )
            close()
        }

        helper.runMigrationsAndValidate(
            testDb,
            3,
            true,
            WorkoutDatabase.MIGRATION_2_3
        ).apply {
            query("PRAGMA table_info(workouts)").use { cursor ->
                var hasIsFavorite = false
                while (cursor.moveToNext()) {
                    if (cursor.getString(cursor.getColumnIndexOrThrow("name")) == "isFavorite") {
                        hasIsFavorite = true
                        break
                    }
                }
                assertTrue(hasIsFavorite)
            }
            query("SELECT isFavorite FROM workouts").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue(cursor.getInt(0) == 0)
            }
            close()
        }
    }

    @Test
    @Throws(IOException::class)
    fun migrate3To4_addsTrainingPlanTables() {
        helper.createDatabase(testDb, 3).apply {
            execSQL(
                """
                INSERT INTO workouts (date, distance, duration, avgPace, calories, notes, type, trackData, isFavorite)
                VALUES (1700000000000, 5.0, 1800000, 6.0, 350, NULL, 'EASY_RUN', NULL, 0)
                """.trimIndent()
            )
            close()
        }

        helper.runMigrationsAndValidate(
            testDb,
            4,
            true,
            WorkoutDatabase.MIGRATION_3_4
        ).apply {
            val expectedTables = listOf(
                "workout_templates",
                "workout_template_segments",
                "training_plans",
                "training_plan_days",
                "plan_schedules",
                "scheduled_workouts"
            )
            expectedTables.forEach { table ->
                query(
                    "SELECT name FROM sqlite_master WHERE type='table' AND name=?",
                    arrayOf(table)
                ).use { cursor ->
                    assertTrue("Missing table $table", cursor.moveToFirst())
                }
            }
            close()
        }
    }

    @Test
    @Throws(IOException::class)
    fun migrate4To5_addsIconKeyColumns() {
        helper.createDatabase(testDb, 4).apply {
            execSQL(
                """
                INSERT INTO workout_templates (name, workoutType, notes, createdAt, updatedAt)
                VALUES ('Intervals', 'INTERVAL_TRAINING', NULL, 1700000000000, 1700000000000)
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO training_plans (name, durationDays, notes, createdAt, updatedAt)
                VALUES ('Base plan', 56, NULL, 1700000000000, 1700000000000)
                """.trimIndent()
            )
            close()
        }

        helper.runMigrationsAndValidate(
            testDb,
            5,
            true,
            WorkoutDatabase.MIGRATION_4_5
        ).apply {
            query("PRAGMA table_info(workout_templates)").use { cursor ->
                var hasIcon = false
                while (cursor.moveToNext()) {
                    if (cursor.getString(cursor.getColumnIndexOrThrow("name")) == "iconKey") {
                        hasIcon = true
                        break
                    }
                }
                assertTrue(hasIcon)
            }
            query("SELECT iconKey FROM workout_templates").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue(cursor.getString(0) == "INTERVAL")
            }
            query("SELECT iconKey FROM training_plans").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue(cursor.getString(0) == "PLAN")
            }
            close()
        }
    }

    @Test
    @Throws(IOException::class)
    fun migrate5To6_addsIntervalSegmentsJsonColumn_keepsWorkout() {
        helper.createDatabase(testDb, 5).apply {
            execSQL(
                """
                INSERT INTO workouts (date, distance, duration, avgPace, calories, notes, type, trackData, isFavorite)
                VALUES (1700000000000, 5.0, 1800000, 6.0, 350, 'note', 'EASY_RUN', '{"points":[]}', 1)
                """.trimIndent()
            )
            close()
        }

        helper.runMigrationsAndValidate(
            testDb,
            6,
            true,
            WorkoutDatabase.MIGRATION_5_6
        ).apply {
            query("SELECT notes, trackData, isFavorite, intervalSegmentsJson FROM workouts").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("note", cursor.getString(0))
                assertEquals("{\"points\":[]}", cursor.getString(1))
                assertEquals(1, cursor.getInt(2))
                assertTrue(cursor.isNull(3))
            }
            close()
        }
    }

    @Test
    @Throws(IOException::class)
    fun migrate6To7_movingDurationIsDuration_newColumnsEmpty() {
        helper.createDatabase(testDb, 6).apply {
            execSQL(
                """
                INSERT INTO workouts (date, distance, duration, avgPace, calories, notes, type, trackData, isFavorite, intervalSegmentsJson)
                VALUES (1700000000000, 5.0, 1800000, 6.0, 350, 'note', 'EASY_RUN', '{"points":[]}', 1, NULL)
                """.trimIndent()
            )
            close()
        }

        helper.runMigrationsAndValidate(
            testDb,
            7,
            true,
            WorkoutDatabase.MIGRATION_6_7
        ).apply {
            query(
                """
                SELECT movingDuration, elevationGain, elevationLoss, elevationSource, avgCadence,
                       routePreview, excludeFromRecords, avgHeartRate, maxHeartRate, metricsVersion
                FROM workouts
                """.trimIndent()
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1_800_000L, cursor.getLong(0))
                for (column in 1..5) assertTrue("column $column", cursor.isNull(column))
                assertEquals(0, cursor.getInt(6))
                assertTrue(cursor.isNull(7))
                assertTrue(cursor.isNull(8))
                assertEquals(0, cursor.getInt(9))
            }
            query("SELECT COUNT(*) FROM best_efforts").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
            close()
        }
    }

    /** Oldest schema → current: the whole chain must land exactly on the latest schema. */
    @Test
    @Throws(IOException::class)
    fun migrateAll1ToLatest_keepsWorkoutAndMatchesSchema() {
        helper.createDatabase(testDb, 1).apply {
            execSQL(
                """
                INSERT INTO workouts (date, distance, duration, avgPace, calories, notes, type)
                VALUES (1700000000000, 10.0, 3600000, 6.0, NULL, NULL, 'LONG_RUN')
                """.trimIndent()
            )
            close()
        }

        val latestVersion = WorkoutDatabase.ALL_MIGRATIONS.last().endVersion
        helper.runMigrationsAndValidate(
            testDb,
            latestVersion,
            true,
            *WorkoutDatabase.ALL_MIGRATIONS
        ).apply {
            query(
                "SELECT distance, type, trackData, isFavorite, intervalSegmentsJson, movingDuration FROM workouts"
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(10.0, cursor.getDouble(0), 0.001)
                assertEquals("LONG_RUN", cursor.getString(1))
                assertTrue(cursor.isNull(2))
                assertEquals(0, cursor.getInt(3))
                assertTrue(cursor.isNull(4))
                assertEquals(3_600_000L, cursor.getLong(5))
            }
            close()
        }
    }
}
