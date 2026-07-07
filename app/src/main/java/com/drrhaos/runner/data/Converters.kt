package com.drrhaos.runner.data

import androidx.room.TypeConverter
import java.util.Date

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
        return WorkoutType.valueOf(value)
    }
}
