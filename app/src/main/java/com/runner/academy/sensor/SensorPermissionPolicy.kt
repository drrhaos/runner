package com.runner.academy.sensor

import android.Manifest
import android.annotation.SuppressLint
import android.os.Build

/** Why a scan for sensors would find nothing. */
enum class ScanBlocker {
    /** A runtime permission from [SensorPermissionPolicy.scanPermissions] is missing. */
    NO_PERMISSION,

    /** 30 and below: the system location switch is off, so BLE scans come back empty. */
    LOCATION_OFF
}

/**
 * Pure decisions for the Bluetooth runtime permissions. 31+ asks for "Nearby devices"
 * (`BLUETOOTH_SCAN` declared `neverForLocation`, plus `BLUETOOTH_CONNECT`) in one request;
 * 30 and below need the precise location (which the app has for GPS anyway) and the location
 * switch on for a scan, and nothing at runtime to connect by address.
 */
object SensorPermissionPolicy {

    const val NEARBY_DEVICES_SDK = Build.VERSION_CODES.S

    /** The constants are inlined from API 31; they are only requested from [NEARBY_DEVICES_SDK]. */
    @SuppressLint("InlinedApi")
    const val SCAN = Manifest.permission.BLUETOOTH_SCAN

    @SuppressLint("InlinedApi")
    const val CONNECT = Manifest.permission.BLUETOOTH_CONNECT

    const val FINE_LOCATION = Manifest.permission.ACCESS_FINE_LOCATION

    /** Runtime permissions to request before a scan (adding a sensor). */
    fun scanPermissions(sdkInt: Int): List<String> =
        if (sdkInt >= NEARBY_DEVICES_SDK) listOf(SCAN, CONNECT) else listOf(FINE_LOCATION)

    /** Runtime permissions to connect to a paired sensor by address (no scan needed). */
    fun connectPermissions(sdkInt: Int): List<String> =
        if (sdkInt >= NEARBY_DEVICES_SDK) listOf(CONNECT) else emptyList()

    fun canConnect(sdkInt: Int, granted: Set<String>): Boolean =
        granted.containsAll(connectPermissions(sdkInt))

    /** What stops a scan, or null when it can run. */
    fun canScan(sdkInt: Int, granted: Set<String>, locationOn: Boolean): ScanBlocker? = when {
        !granted.containsAll(scanPermissions(sdkInt)) -> ScanBlocker.NO_PERMISSION
        sdkInt < NEARBY_DEVICES_SDK && !locationOn -> ScanBlocker.LOCATION_OFF
        else -> null
    }
}
