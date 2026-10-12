package com.runner.academy.ui.devices

import android.bluetooth.BluetoothAdapter
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.core.content.ContextCompat
import com.runner.academy.sensor.SensorPermissionPolicy

private const val TAG = "BluetoothIntents"

/**
 * The system "Turn on Bluetooth?" request. It needs `BLUETOOTH_CONNECT` on 31+; without it
 * (not expected: "Bluetooth is off" is only shown with it) the Bluetooth settings page.
 */
fun bluetoothEnableIntent(context: Context): Intent {
    val sdk = Build.VERSION.SDK_INT
    val canConnect = SensorPermissionPolicy.canConnect(sdk, grantedOf(context, SensorPermissionPolicy.connectPermissions(sdk)))
    return if (canConnect) Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE) else Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
}

/** Asks to turn Bluetooth on through [launcher]; the result comes back to it. */
fun requestBluetoothEnable(context: Context, launcher: ActivityResultLauncher<Intent>) {
    try {
        launcher.launch(bluetoothEnableIntent(context))
    } catch (e: ActivityNotFoundException) {
        Log.w(TAG, "No Bluetooth request activity: ${e.message}")
    } catch (e: SecurityException) {
        Log.w(TAG, "Bluetooth request refused: ${e.message}")
    }
}

/** Granted runtime permissions out of [permissions]. */
fun grantedOf(context: Context, permissions: List<String>): Set<String> =
    permissions.filter { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }.toSet()
