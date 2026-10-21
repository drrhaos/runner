package com.runner.academy.sensor

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.os.HandlerCompat

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

/** [LinkScheduler] on a `Handler` of the BLE thread. */
class HandlerScheduler(private val handler: Handler) : LinkScheduler {

    override fun post(task: () -> Unit) {
        handler.post(task)
    }

    override fun postDelayed(token: Any, delayMs: Long, task: () -> Unit) {
        HandlerCompat.postDelayed(handler, task, token, delayMs)
    }

    override fun cancel(token: Any) {
        handler.removeCallbacksAndMessages(token)
    }
}

/** `ACTION_STATE_CHANGED` of the adapter, delivered on [handler]. */
class AndroidAdapterWatch(private val context: Context, private val handler: Handler) : AdapterStateWatch {

    private var receiver: BroadcastReceiver? = null

    override fun start(onState: (Int) -> Unit) {
        stop()
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                onState(intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR))
            }
        }
        // Exported: below 33 ContextCompat makes a not-exported receiver require an app-private
        // permission, which the Bluetooth process sending this does not hold. Safe: it is a
        // protected broadcast, no other app can send it.
        ContextCompat.registerReceiver(
            context, r, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), null, handler,
            ContextCompat.RECEIVER_EXPORTED
        )
        receiver = r
    }

    override fun stop() {
        receiver?.let { context.unregisterReceiver(it) }
        receiver = null
    }
}
