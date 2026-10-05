package com.runner.academy.data

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import android.content.Context

@Database(
    entities = [
        Workout::class,
        WorkoutTemplate::class,
        WorkoutTemplateSegment::class,
        TrainingPlan::class,
        TrainingPlanDay::class,
        PlanSchedule::class,
        ScheduledWorkout::class,
        BestEffort::class
    ],
    version = 7,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class WorkoutDatabase : RoomDatabase() {
    abstract fun workoutDao(): WorkoutDao
    abstract fun workoutTemplateDao(): WorkoutTemplateDao
    abstract fun trainingPlanDao(): TrainingPlanDao
    abstract fun planScheduleDao(): PlanScheduleDao
    abstract fun bestEffortDao(): BestEffortDao

    companion object {
        @Volatile
        private var INSTANCE: WorkoutDatabase? = null

        internal val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE workouts ADD COLUMN trackData TEXT")
            }
        }

        internal val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE workouts ADD COLUMN isFavorite INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        internal val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `workout_templates` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `workoutType` TEXT NOT NULL,
                        `notes` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `workout_template_segments` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `templateId` INTEGER NOT NULL,
                        `sortOrder` INTEGER NOT NULL,
                        `kind` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `goalType` TEXT NOT NULL,
                        `durationMs` INTEGER,
                        `distanceMeters` REAL,
                        `targetPaceMinPerKm` REAL,
                        FOREIGN KEY(`templateId`) REFERENCES `workout_templates`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_workout_template_segments_templateId` ON `workout_template_segments` (`templateId`)"
                )
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `training_plans` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `durationDays` INTEGER NOT NULL,
                        `notes` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `training_plan_days` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `planId` INTEGER NOT NULL,
                        `dayIndex` INTEGER NOT NULL,
                        `templateId` INTEGER,
                        FOREIGN KEY(`planId`) REFERENCES `training_plans`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(`templateId`) REFERENCES `workout_templates`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_training_plan_days_planId` ON `training_plan_days` (`planId`)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_training_plan_days_templateId` ON `training_plan_days` (`templateId`)"
                )
                database.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_training_plan_days_planId_dayIndex` ON `training_plan_days` (`planId`, `dayIndex`)"
                )
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `plan_schedules` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `planId` INTEGER NOT NULL,
                        `startDateMillis` INTEGER NOT NULL,
                        `isActive` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        FOREIGN KEY(`planId`) REFERENCES `training_plans`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_plan_schedules_planId` ON `plan_schedules` (`planId`)"
                )
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `scheduled_workouts` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `scheduleId` INTEGER NOT NULL,
                        `dateMillis` INTEGER NOT NULL,
                        `dayIndex` INTEGER NOT NULL,
                        `templateId` INTEGER,
                        `status` TEXT NOT NULL,
                        `completedWorkoutId` INTEGER,
                        FOREIGN KEY(`scheduleId`) REFERENCES `plan_schedules`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(`templateId`) REFERENCES `workout_templates`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_scheduled_workouts_scheduleId` ON `scheduled_workouts` (`scheduleId`)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_scheduled_workouts_dateMillis` ON `scheduled_workouts` (`dateMillis`)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_scheduled_workouts_templateId` ON `scheduled_workouts` (`templateId`)"
                )
            }
        }

        internal val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE workout_templates ADD COLUMN iconKey TEXT NOT NULL DEFAULT 'INTERVAL'"
                )
                database.execSQL(
                    "ALTER TABLE training_plans ADD COLUMN iconKey TEXT NOT NULL DEFAULT 'PLAN'"
                )
            }
        }

        internal val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE workouts ADD COLUMN intervalSegmentsJson TEXT"
                )
            }
        }

        /**
         * Release 3 metrics. Only `movingDuration` is filled here (= duration: old rows had no
         * auto-pause); the derived columns stay empty with `metricsVersion = 0` for the background
         * metrics pass. DEFAULT values must match the entity's `@ColumnInfo(defaultValue)`.
         */
        internal val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE workouts ADD COLUMN movingDuration INTEGER NOT NULL DEFAULT 0"
                )
                database.execSQL("UPDATE workouts SET movingDuration = duration")
                database.execSQL("ALTER TABLE workouts ADD COLUMN elevationGain REAL")
                database.execSQL("ALTER TABLE workouts ADD COLUMN elevationLoss REAL")
                database.execSQL("ALTER TABLE workouts ADD COLUMN elevationSource TEXT")
                database.execSQL("ALTER TABLE workouts ADD COLUMN avgCadence REAL")
                database.execSQL("ALTER TABLE workouts ADD COLUMN routePreview TEXT")
                database.execSQL(
                    "ALTER TABLE workouts ADD COLUMN excludeFromRecords INTEGER NOT NULL DEFAULT 0"
                )
                database.execSQL("ALTER TABLE workouts ADD COLUMN avgHeartRate INTEGER")
                database.execSQL("ALTER TABLE workouts ADD COLUMN maxHeartRate INTEGER")
                database.execSQL(
                    "ALTER TABLE workouts ADD COLUMN metricsVersion INTEGER NOT NULL DEFAULT 0"
                )
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `best_efforts` (
                        `workoutId` INTEGER NOT NULL,
                        `distanceM` INTEGER NOT NULL,
                        `elapsedMs` INTEGER NOT NULL,
                        `startTime` INTEGER NOT NULL,
                        `endTime` INTEGER NOT NULL,
                        `stepsShare` REAL NOT NULL,
                        PRIMARY KEY(`workoutId`, `distanceM`),
                        FOREIGN KEY(`workoutId`) REFERENCES `workouts`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_best_efforts_workoutId` ON `best_efforts` (`workoutId`)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_best_efforts_distanceM_elapsedMs` ON `best_efforts` (`distanceM`, `elapsedMs`)"
                )
            }
        }

        /** Every migration, oldest first — shared with the migration tests. Append new ones here. */
        internal val ALL_MIGRATIONS = arrayOf(
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5,
            MIGRATION_5_6,
            MIGRATION_6_7
        )

        fun getDatabase(context: Context): WorkoutDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    WorkoutDatabase::class.java,
                    "workout_database"
                )
                    .addMigrations(*ALL_MIGRATIONS)
                    .build()
                INSTANCE = instance
                instance
            }
        }

        fun getInMemoryDatabase(context: Context): WorkoutDatabase {
            return Room.inMemoryDatabaseBuilder(
                context.applicationContext,
                WorkoutDatabase::class.java
            )
                .allowMainThreadQueries()
                .build()
        }
    }
}
