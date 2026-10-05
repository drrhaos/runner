package com.runner.academy.data

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * The fastest window of one workout over one [RecordDistance]. Records and their history are
 * derived when read (the best row per distance over workouts not excluded from records), so
 * deleting, excluding or re-dating a workout needs no recomputation of the others.
 */
@Entity(
    tableName = "best_efforts",
    primaryKeys = ["workoutId", "distanceM"],
    foreignKeys = [
        ForeignKey(
            entity = Workout::class,
            parentColumns = ["id"],
            childColumns = ["workoutId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["workoutId"]),
        Index(value = ["distanceM", "elapsedMs"])
    ]
)
data class BestEffort(
    val workoutId: Long,
    /** [RecordDistance.meters]: distances are rows, so a new one needs no migration. */
    val distanceM: Int,
    /** Wall-clock time of the window, pauses inside it included. */
    val elapsedMs: Long,
    /** Timestamps of the window ends in the track. */
    val startTime: Long,
    val endTime: Long,
    /** Share of the window bridged by steps (0..1); above 0 the record is marked approximate. */
    val stepsShare: Float
)

/** A best effort with the workout columns records are ordered and labelled by. */
data class EffortRow(
    @Embedded val effort: BestEffort,
    val date: java.util.Date,
    val type: WorkoutType
)

/** Record distances with the men's world record as a sanity bound (a faster window is a glitch). */
enum class RecordDistance(val meters: Int, val worldRecordMs: Long) {
    KM_1(1_000, 131_960L),
    KM_5(5_000, 755_360L),
    KM_10(10_000, 1_571_000L),
    HALF_MARATHON(21_097, 3_402_000L),
    MARATHON(42_195, 7_235_000L);

    companion object {
        fun ofMeters(meters: Int): RecordDistance? = entries.find { it.meters == meters }
    }
}
