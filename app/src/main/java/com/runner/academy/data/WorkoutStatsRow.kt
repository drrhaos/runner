package com.runner.academy.data

import java.util.Date

/** The columns statistics read — without the (large) track JSON. */
data class WorkoutStatsRow(
    val date: Date,
    val distance: Float,
    val duration: Long,
    val avgPace: Float,
    val calories: Int?,
    val type: WorkoutType
)
