package com.runner.academy.data

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
        return WorkoutType.values().find { it.name == value } ?: run {
            Log.w(TAG, "Unknown workout type '$value', defaulting to EASY_RUN")
            WorkoutType.EASY_RUN
        }
    }

    @TypeConverter
    fun fromSegmentKind(value: SegmentKind): String = value.name

    @TypeConverter
    fun toSegmentKind(value: String): SegmentKind =
        SegmentKind.entries.find { it.name == value } ?: SegmentKind.CUSTOM

    @TypeConverter
    fun fromSegmentGoalType(value: SegmentGoalType): String = value.name

    @TypeConverter
    fun toSegmentGoalType(value: String): SegmentGoalType =
        SegmentGoalType.entries.find { it.name == value } ?: SegmentGoalType.DURATION

    @TypeConverter
    fun fromScheduledWorkoutStatus(value: ScheduledWorkoutStatus): String = value.name

    @TypeConverter
    fun toScheduledWorkoutStatus(value: String): ScheduledWorkoutStatus =
        ScheduledWorkoutStatus.entries.find { it.name == value } ?: ScheduledWorkoutStatus.PLANNED
}
