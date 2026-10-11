package com.runner.academy.sensor

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat

/**
 * Bluetooth LE on this device: the adapter, the BLE thread (`HandlerThread("ble")`, where every
 * GATT callback, timer, machine step and sensor update runs) and new links on it. One per
 * process ([com.runner.academy.AppContainer]); [createOrNull] is null without Bluetooth LE.
 */
class AndroidBle private constructor(private val context: Context, val adapter: BluetoothAdapter) {

    private val thread = HandlerThread("ble").apply { start() }
    private val handler = Handler(thread.looper)

    val scheduler: LinkScheduler = HandlerScheduler(handler)

    /** Whether the Bluetooth switch is on (no runtime permission needed). */
    val bluetoothOn: Boolean get() = adapter.isEnabled

    /** A link for one device; each watches the adapter switch on its own. */
    fun newLink(): GattLink = BleGattLink(
        AndroidGattConnector(context, adapter, handler),
        AndroidAdapterWatch(context, handler),
        scheduler,
        elapsedNanos = SystemClock::elapsedRealtimeNanos
    )

    fun canConnect(): Boolean = SensorPermissionPolicy.canConnect(
        Build.VERSION.SDK_INT, granted(SensorPermissionPolicy.connectPermissions(Build.VERSION.SDK_INT))
    )

    /** What blocks a scan now, or null. */
    fun scanBlocker(): ScanBlocker? {
        val sdk = Build.VERSION.SDK_INT
        val locationManager = context.getSystemService(LocationManager::class.java)
        val locationOn = locationManager != null && LocationManagerCompat.isLocationEnabled(locationManager)
        return SensorPermissionPolicy.canScan(sdk, granted(SensorPermissionPolicy.scanPermissions(sdk)), locationOn)
    }

    private fun granted(permissions: List<String>): Set<String> =
        permissions.filter { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }.toSet()

    companion object {
        fun createOrNull(context: Context): AndroidBle? {
            val app = context.applicationContext
            if (!app.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) return null
            val adapter = app.getSystemService(BluetoothManager::class.java)?.adapter ?: return null
            return AndroidBle(app, adapter)
        }
    }
}
