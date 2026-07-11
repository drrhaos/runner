package com.example.runner.data

import androidx.room.TypeConverter
import android.util.Log
import java.util.Date

private const val TAG = "Converters"

class Converters {
    @TypeConverter
    fun fromTimestamp(value: Long?): Date? {
        return value?.let { Date(it) }
    }

    @TypeConverter
    fun dateToTimestamp(date: Date?): Long? {
        return date?.time
    }

    @TypeConverter
    fun fromWorkoutType(value: WorkoutType): String {
        return value.name
    }

    @TypeConverter
    fun toWorkoutType(value: String): WorkoutType {
        return WorkoutType.valueOfOrNull(value) ?: run {
            Log.w(TAG, "Unknown workout type '$value', defaulting to EASY_RUN")
            WorkoutType.EASY_RUN
        }
    }
}
