package com.runner.academy.data

/**
 * The pace of the last 30 s of moving time ([com.runner.academy.service.LivePaceTracker]).
 *
 * @param minPerKm minutes per kilometre.
 * @param approximate counted from steps (GPS stale or false): the tile marks it with «≈».
 */
data class LivePace(val minPerKm: Float, val approximate: Boolean)
