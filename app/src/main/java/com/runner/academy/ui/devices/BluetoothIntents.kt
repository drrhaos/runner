package com.runner.academy.ui.devices

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.runner.academy.sensor.SensorPermissionPolicy

/**
 * The system "Turn on Bluetooth?" request. It needs `BLUETOOTH_CONNECT` on 31+; without it
 * (not expected: "Bluetooth is off" is only shown with it) the Bluetooth settings page.
 */
fun bluetoothEnableIntent(context: Context): Intent {
    val connectGranted = SensorPermissionPolicy.connectPermissions(Build.VERSION.SDK_INT).all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
    return if (connectGranted) Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE) else Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
}

/** Granted runtime permissions out of [permissions]. */
fun grantedOf(context: Context, permissions: List<String>): Set<String> =
    permissions.filter { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }.toSet()
