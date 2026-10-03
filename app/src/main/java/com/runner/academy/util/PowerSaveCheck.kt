package com.runner.academy.util

import android.content.Context
import android.os.Build
import android.os.PowerManager

/**
 * Whether battery saver may cut GPS while the screen is off — the location foreground
 * service does not protect against it. Checked on every app launch and recorded in
 * GPS diagnostics.
 */
object PowerSaveCheck {

    // Values of PowerManager.LOCATION_MODE_* (kept here for JVM tests)
    const val LOCATION_MODE_NO_CHANGE = 0
    const val LOCATION_MODE_GPS_DISABLED_WHEN_SCREEN_OFF = 1
    const val LOCATION_MODE_ALL_DISABLED_WHEN_SCREEN_OFF = 2
    const val LOCATION_MODE_FOREGROUND_ONLY = 3
    /** Added in API 29. */
    const val LOCATION_MODE_THROTTLE_REQUESTS_WHEN_SCREEN_OFF = 4

    /**
     * [locationMode] is one of the LOCATION_MODE_* values, or null when the platform does
     * not report it (API < 28).
     */
    data class Status(val powerSaveMode: Boolean, val locationMode: Int?)

    /** True when this status may stop or throttle GPS for a workout with the screen off. */
    fun affectsScreenOffTracking(status: Status): Boolean {
        if (!status.powerSaveMode) return false
        return when (status.locationMode) {
            // A location foreground service counts as foreground
            LOCATION_MODE_NO_CHANGE, LOCATION_MODE_FOREGROUND_ONLY -> false
            // null: before API 28 battery saver turned GPS off with the screen off;
            // unknown future modes are assumed to cut GPS too
            else -> true
        }
    }

    /** Stable name of [locationMode] for the diagnostics file ("unknown" for null / new values). */
    fun locationModeName(locationMode: Int?): String = when (locationMode) {
        LOCATION_MODE_NO_CHANGE -> "no_change"
        LOCATION_MODE_GPS_DISABLED_WHEN_SCREEN_OFF -> "gps_disabled_when_screen_off"
        LOCATION_MODE_ALL_DISABLED_WHEN_SCREEN_OFF -> "all_disabled_when_screen_off"
        LOCATION_MODE_FOREGROUND_ONLY -> "foreground_only"
        LOCATION_MODE_THROTTLE_REQUESTS_WHEN_SCREEN_OFF -> "throttle_requests_when_screen_off"
        else -> "unknown"
    }

    fun read(context: Context): Status {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return Status(powerSaveMode = false, locationMode = null)
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pm.locationPowerSaveMode else null
        return Status(powerSaveMode = pm.isPowerSaveMode, locationMode = mode)
    }
}
