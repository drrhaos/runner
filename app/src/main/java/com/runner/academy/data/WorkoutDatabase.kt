package com.runner.academy.data

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import android.content.Context

@Database(
    entities = [Workout::class],
    version = 3,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class WorkoutDatabase : RoomDatabase() {
    abstract fun workoutDao(): WorkoutDao

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

        fun getDatabase(context: Context): WorkoutDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    WorkoutDatabase::class.java,
                    "workout_database"
                )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
                INSTANCE = instance
                instance
            }
        }

        /**
         * Создает in-memory базу данных для тестирования
         */
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
