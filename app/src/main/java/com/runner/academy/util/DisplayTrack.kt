package com.runner.academy.util

import com.runner.academy.data.TrackData
import com.runner.academy.data.WorkoutType

/**
 * The track as the user sees it: the stored JSON, cleaned of GPS outliers when it has many
 * ([WorkoutDataCleaner.needsCleaning]). The details screen and [WorkoutDerivation] both read
 * it, so derived metrics (records, splits) agree with the map and the charts. The stored
 * track itself is never rewritten.
 */
object DisplayTrack {

    /** Null when there is no track or it cannot be parsed. */
    fun of(trackJson: String?, type: WorkoutType): TrackData? {
        val track = TrackDataJson.parse(trackJson) ?: return null
        if (track.points.isEmpty() || !WorkoutDataCleaner.needsCleaning(track)) return track
        return WorkoutDataCleaner.cleanTrackData(track, type)
    }
}
