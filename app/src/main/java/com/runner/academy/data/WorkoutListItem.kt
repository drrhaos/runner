package com.runner.academy.data

import java.util.Date

/**
 * A row of the workout list and the route picker: the columns they show, without the (large)
 * track JSON. The route is drawn from [routePreview]; the track itself is read by id
 * ([WorkoutDao.getTrackData]) only when it is needed.
 */
data class WorkoutListItem(
    val id: Long,
    val date: Date,
    val distance: Float,
    val duration: Long,
    val movingDuration: Long,
    val avgPace: Float,
    val calories: Int?,
    val notes: String?,
    val type: WorkoutType,
    val isFavorite: Boolean,
    /** `trackData IS NOT NULL`: SQLite reads only the record header, not the track. */
    val hasTrack: Boolean,
    val routePreview: String?,
    val metricsVersion: Int
)
