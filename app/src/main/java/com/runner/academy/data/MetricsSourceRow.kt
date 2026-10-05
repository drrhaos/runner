package com.runner.academy.data

/** The columns derived metrics are computed from (see [WorkoutDao.getMetricsSource]). */
data class MetricsSourceRow(
    val id: Long,
    val trackData: String?,
    val type: WorkoutType,
    val duration: Long
)
