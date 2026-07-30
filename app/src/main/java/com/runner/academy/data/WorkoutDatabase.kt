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
        ScheduledWorkout::class
    ],
    version = 4,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class WorkoutDatabase : RoomDatabase() {
    abstract fun workoutDao(): WorkoutDao
    abstract fun workoutTemplateDao(): WorkoutTemplateDao
    abstract fun trainingPlanDao(): TrainingPlanDao
    abstract fun planScheduleDao(): PlanScheduleDao

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

        fun getDatabase(context: Context): WorkoutDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    WorkoutDatabase::class.java,
                    "workout_database"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
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
