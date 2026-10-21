package com.runner.academy.data

/**
 * A lap an imported file has (FIT `lap`, TCX `Lap`), kept in [Workout.lapsJson]. Only [start]
 * is always known; the pace is computed from [timerMs] and [distanceM].
 */
data class Lap(
    /** Epoch millis. */
    val start: Long,
    /** The time the device counted, its pauses left out. */
    val timerMs: Long?,
    val distanceM: Float?,
    val avgHr: Int?,
    val maxHr: Int?,
    /** Steps per minute. */
    val avgCadence: Float?
)
