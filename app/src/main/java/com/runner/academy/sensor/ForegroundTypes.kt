package com.runner.academy.sensor

import android.content.pm.ServiceInfo
import android.os.Build

/**
 * The foreground service types of the workout service. LOCATION always; CONNECTED_DEVICE on
 * 34+ only with a paired sensor and `BLUETOOTH_CONNECT` granted: there it is required for
 * Bluetooth and starting with it without the permission throws `SecurityException`. Below 34
 * types have no preconditions and location alone covers the service.
 */
object ForegroundTypes {

    const val CONNECTED_DEVICE_SDK = Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    fun of(sdkInt: Int, sensorPaired: Boolean, connectGranted: Boolean): Int {
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        if (sdkInt >= CONNECTED_DEVICE_SDK && sensorPaired && connectGranted) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        }
        return types
    }
}
