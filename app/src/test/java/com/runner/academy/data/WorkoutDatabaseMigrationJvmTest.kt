package com.runner.academy.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Migrations on the JVM, without an emulator. Room's MigrationTestHelper reads schemas from
 * test assets, which Robolectric does not see; so an old database is built from the exported
 * schema's `createSql` (classpath resources, see build.gradle.kts) and then opened with Room at
 * the current version. Room runs the migrations and validates the resulting schema against the
 * entities on open, exactly as on a device. [WorkoutDatabaseMigrationTest] in androidTest repeats
 * the checks with MigrationTestHelper on a device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WorkoutDatabaseMigrationJvmTest {

    private lateinit var context: Context
    private lateinit var dbFile: File
    private var room: WorkoutDatabase? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dbFile = context.getDatabasePath("migration-jvm-test")
        dbFile.parentFile?.mkdirs()
        context.deleteDatabase(dbFile.name)
    }

    @After
    fun tearDown() {
        room?.close()
        context.deleteDatabase(dbFile.name)
    }

    @Test
    fun migrate6To7_movingDurationIsDuration_newColumnsEmpty() {
        createDatabase(6) {
            execSQL(
                """
                INSERT INTO workouts (date, distance, duration, avgPace, calories, notes, type, trackData, isFavorite, intervalSegmentsJson)
                VALUES (1700000000000, 5.0, 1800000, 6.0, 350, 'note', 'EASY_RUN', '{"points":[]}', 1, NULL)
                """.trimIndent()
            )
        }

        openWithRoom(WorkoutDatabase.MIGRATION_6_7).apply {
            query(
                """
                SELECT duration, movingDuration, elevationGain, elevationLoss, elevationSource,
                       avgCadence, routePreview, excludeFromRecords, avgHeartRate, maxHeartRate,
                       metricsVersion, notes, isFavorite
                FROM workouts
                """.trimIndent()
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1_800_000L, cursor.getLong(0))
                assertEquals(1_800_000L, cursor.getLong(1))
                for (column in 2..6) assertTrue("column $column", cursor.isNull(column))
                assertEquals(0, cursor.getInt(7))
                assertTrue(cursor.isNull(8))
                assertTrue(cursor.isNull(9))
                assertEquals(0, cursor.getInt(10))
                assertEquals("note", cursor.getString(11))
                assertEquals(1, cursor.getInt(12))
            }
            assertEquals(0, count("best_efforts"))
        }
    }

    @Test
    fun migrate6To7_migratedRowReadsBackAsEntity() = runBlocking {
        createDatabase(6) {
            execSQL(
                """
                INSERT INTO workouts (date, distance, duration, avgPace, calories, notes, type, trackData, isFavorite, intervalSegmentsJson)
                VALUES (1700000000000, 5.0, 1800000, 6.0, NULL, NULL, 'RACE', NULL, 0, NULL)
                """.trimIndent()
            )
        }
        openWithRoom(WorkoutDatabase.MIGRATION_6_7)

        val rows = requireNotNull(room).workoutDao().getStatsRows()

        assertEquals(1, rows.size)
        assertEquals(1_800_000L, rows[0].movingDuration)
        assertEquals(null, rows[0].elevationSource)
    }

    /** Oldest schema → current: the whole chain must land exactly on the latest schema. */
    @Test
    fun migrateAll1ToLatest_keepsWorkoutAndMatchesSchema() {
        createDatabase(1) {
            execSQL(
                """
                INSERT INTO workouts (date, distance, duration, avgPace, calories, notes, type)
                VALUES (1700000000000, 10.0, 3600000, 6.0, NULL, NULL, 'LONG_RUN')
                """.trimIndent()
            )
        }

        openWithRoom(*WorkoutDatabase.ALL_MIGRATIONS).apply {
            assertEquals(WorkoutDatabase.ALL_MIGRATIONS.last().endVersion, version)
            query("SELECT distance, type, isFavorite, movingDuration, metricsVersion FROM workouts").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(10.0, cursor.getDouble(0), 0.001)
                assertEquals("LONG_RUN", cursor.getString(1))
                assertEquals(0, cursor.getInt(2))
                assertEquals(3_600_000L, cursor.getLong(3))
                assertEquals(0, cursor.getInt(4))
                assertFalse(cursor.moveToNext())
            }
        }
    }

    /** Builds the database of schema [version] as Room created it, then lets [fill] add rows. */
    private fun createDatabase(version: Int, fill: SQLiteDatabase.() -> Unit) {
        val schema = readSchema(version)
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            schema.getAsJsonArray("entities").forEach { element ->
                val entity = element.asJsonObject
                val table = entity.get("tableName").asString
                db.execSQL(entity.get("createSql").asString.replace("\${TABLE_NAME}", table))
                entity.getAsJsonArray("indices")?.forEach { index ->
                    db.execSQL(index.asJsonObject.get("createSql").asString.replace("\${TABLE_NAME}", table))
                }
            }
            schema.getAsJsonArray("views")?.forEach { view ->
                val name = view.asJsonObject.get("viewName").asString
                db.execSQL(view.asJsonObject.get("createSql").asString.replace("\${VIEW_NAME}", name))
            }
            schema.getAsJsonArray("setupQueries")?.forEach { db.execSQL(it.asString) }
            db.version = version
            db.fill()
        }
    }

    /** Opens the file with Room at the current version: migrates, then validates the schema. */
    private fun openWithRoom(vararg migrations: androidx.room.migration.Migration): SupportSQLiteDatabase {
        val database = Room.databaseBuilder(context, WorkoutDatabase::class.java, dbFile.name)
            .addMigrations(*migrations)
            .allowMainThreadQueries()
            .build()
        room = database
        return database.openHelper.writableDatabase
    }

    private fun readSchema(version: Int): JsonObject {
        val path = "com.runner.academy.data.WorkoutDatabase/$version.json"
        val text = requireNotNull(javaClass.classLoader?.getResource(path)) { "missing schema $path" }
            .readText()
        return JsonParser.parseString(text).asJsonObject.getAsJsonObject("database")
    }

    private fun SupportSQLiteDatabase.count(table: String): Int =
        query("SELECT COUNT(*) FROM $table").use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }
}
