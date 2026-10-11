package com.runner.academy.sensor

import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.ParcelUuid
import android.os.SystemClock
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

/**
 * [SensorScanner] over `BluetoothLeScanner`: low-latency scan filtered by the Heart Rate
 * Service UUID (no location from 31+: `BLUETOOTH_SCAN` is `neverForLocation`), and the probe
 * through a [SensorProbe] on the BLE thread with a link of its own.
 *
 * @param holdOff keeps the heart-rate sensor off a device while it is probed
 *   ([BleHeartRateSensor.holdOff]): one GATT client per device.
 */
class BleScanner(
    private val ble: AndroidBle,
    private val holdOff: (String) -> SensorLease
) : SensorScanner {

    /** Scan starts of this process, oldest first ([ScanStartLimit]). */
    private var starts = emptyList<Long>()
    private val lock = Any()

    override fun waitBeforeScanMs(): Long =
        synchronized(lock) { ScanStartLimit.waitMs(starts, SystemClock.elapsedRealtime()) }

    override fun scan(): Flow<List<FoundSensor>> = callbackFlow {
        when (ble.scanBlocker()) {
            ScanBlocker.NO_PERMISSION -> throw ScanException(ScanFailure.NO_PERMISSION)
            ScanBlocker.LOCATION_OFF -> throw ScanException(ScanFailure.LOCATION_OFF)
            null -> Unit
        }
        if (!ble.bluetoothOn) throw ScanException(ScanFailure.BLUETOOTH_OFF)
        val scanner = ble.adapter.bluetoothLeScanner ?: throw ScanException(ScanFailure.BLUETOOTH_OFF)
        val wait = waitBeforeScanMs()
        if (wait > 0) throw ScanException(ScanFailure.TOO_FREQUENT, waitMs = wait)

        // Scan callbacks come on the main thread, one at a time
        var found = emptyList<FoundSensor>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                found = found.withFound(
                    FoundSensor(result.device.address, result.scanRecord?.deviceName, result.rssi)
                )
                trySend(found)
            }

            override fun onScanFailed(errorCode: Int) {
                close(ScanException(ScanFailure.FAILED, errorCode = errorCode))
            }
        }
        val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(GattUuids.HEART_RATE_SERVICE)).build())
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            scanner.startScan(filters, settings, callback)
        } catch (e: SecurityException) {
            throw scanStartFailure(e)
        } catch (e: IllegalStateException) {
            throw scanStartFailure(e)
        }
        synchronized(lock) { starts = ScanStartLimit.record(starts, SystemClock.elapsedRealtime()) }
        trySend(found)

        val timer = launch {
            delay(SensorScanner.SCAN_DURATION_MS)
            channel.close()
        }
        awaitClose {
            timer.cancel()
            try {
                scanner.stopScan(callback)
            } catch (e: SecurityException) {
                // Revoked mid-scan: the system stops it with the process
            } catch (e: IllegalStateException) {
                // Bluetooth went off: the scan is gone already
            }
        }
    }

    override suspend fun probe(address: String): ProbeResult {
        if (!ble.canConnect()) return ProbeResult.Failed(ProbeFailure.NO_PERMISSION)
        if (!ble.bluetoothOn) return ProbeResult.Failed(ProbeFailure.BLUETOOTH_OFF)
        return SensorProbe.run(address, ble.newLink(), ble.scheduler, SystemClock::elapsedRealtime, holdOff)
    }
}

/** The scanner on a device without Bluetooth LE. */
object UnavailableSensorScanner : SensorScanner {
    override fun waitBeforeScanMs(): Long = 0L

    override fun scan(): Flow<List<FoundSensor>> = flow { throw ScanException(ScanFailure.UNSUPPORTED) }

    override suspend fun probe(address: String): ProbeResult = ProbeResult.Failed(ProbeFailure.UNREACHABLE)
}
