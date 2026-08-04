package com.runner.academy.util

/**
 * Shared distance / time / speed unit constants and conversions.
 */
object SpeedPaceUnits {
    const val METERS_PER_KM = 1000f
    const val KM_PER_MILE = 1.60934f
    const val MS_PER_MINUTE = 60_000f
    const val MS_PER_HOUR = 3_600_000f
    private const val KPH_TO_MPH_COEF = 0.621371

    fun metersToKm(meters: Float): Float = meters / METERS_PER_KM

    fun kmToMiles(km: Float): Float = km / KM_PER_MILE

    fun speedMsToKmh(speedMs: Float): Float = speedMs * 3.6f

    fun kmhToMph(speedKmh: Float): Float = (speedKmh * KPH_TO_MPH_COEF).toFloat()
}
