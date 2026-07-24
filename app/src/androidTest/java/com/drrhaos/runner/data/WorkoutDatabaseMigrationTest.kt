package com.drrhaos.runner.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
}
