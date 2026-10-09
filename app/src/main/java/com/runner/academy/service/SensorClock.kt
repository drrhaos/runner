package com.runner.academy.service

import kotlin.math.abs

/**
 * The clock a sensor stamps its events with. It should be `elapsedRealtimeNanos`, but some
 * devices use uptime (no deep sleep): on a phone that slept less than a few minutes since boot
 * such a stamp still looks plausible, only shifted by the sleep. [detect] decides once, from
 * an event that has just arrived; [toElapsed] then puts every event on elapsed realtime.
 */
enum class SensorClock {
    ELAPSED_REALTIME,
    UPTIME,

    /** Neither clock: the event's stamp is not used, the time of delivery is. */
    DELIVERY;

    /** [eventNanos] on elapsed realtime, given both clocks read now. */
    fun toElapsed(eventNanos: Long, elapsedNanos: Long, uptimeNanos: Long): Long = when (this) {
        ELAPSED_REALTIME -> eventNanos
        UPTIME -> eventNanos + (elapsedNanos - uptimeNanos)
        DELIVERY -> elapsedNanos
    }

    companion object {
        /** A fresh event is stamped within this of the right clock's now. */
        const val DEFAULT_TOLERANCE_NANOS = 1_000_000_000L

        /**
         * The clock of [eventNanos], an event delivered just now: the closer of elapsed realtime
         * and uptime when within [toleranceNanos] of it (elapsed realtime on a tie — without
         * any sleep they agree), else [DELIVERY]. A stamp that is not positive is [DELIVERY].
         */
        fun detect(
            eventNanos: Long,
            elapsedNanos: Long,
            uptimeNanos: Long,
            toleranceNanos: Long = DEFAULT_TOLERANCE_NANOS
        ): SensorClock {
            if (eventNanos <= 0L) return DELIVERY
            val offElapsed = abs(eventNanos - elapsedNanos)
            val offUptime = abs(eventNanos - uptimeNanos)
            return when {
                offElapsed <= offUptime && offElapsed < toleranceNanos -> ELAPSED_REALTIME
                offUptime < toleranceNanos -> UPTIME
                else -> DELIVERY
            }
        }
    }
}
