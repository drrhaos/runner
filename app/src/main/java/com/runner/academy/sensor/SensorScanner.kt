package com.runner.academy.sensor

import kotlinx.coroutines.flow.Flow

/**
 * Finding and checking a heart-rate sensor to pair (the add-sensor screen). The Android one is
 * [BleScanner]; without Bluetooth LE on the device ([UnavailableSensorScanner]) a scan fails
 * with [ScanFailure.UNSUPPORTED] (the screen should not offer one).
 */
interface SensorScanner {

    /**
     * How long until a scan may start: Android silently ignores (no results, no error) a sixth
     * start within 30 s, so "Retry" must wait. 0: now.
     */
    fun waitBeforeScanMs(): Long

    /**
     * Scans for devices advertising the Heart Rate Service for [SCAN_DURATION_MS], then
     * completes; cancelling the collection stops it early. Emits an empty list once the scan is
     * running, then the devices found so far after each advertisement (see [withFound]).
     *
     * Fails with [ScanException] when it cannot run or the system stops it.
     */
    fun scan(): Flow<List<FoundSensor>>

    /**
     * The check before pairing: connect, discover, the Heart Rate Service is there,
     * notifications turn on, the battery is read; then disconnect. Up to
     * [SensorProbe.PROBE_TIMEOUT_MS]. Cancelling the call disconnects.
     */
    suspend fun probe(address: String): ProbeResult

    companion object {
        const val SCAN_DURATION_MS = 20_000L
    }
}

/**
 * A device seen in a scan.
 *
 * @param name the advertised name, null when the advertisement has none.
 * @param rssi the signal of the last advertisement, dBm.
 */
data class FoundSensor(val address: String, val name: String?, val rssi: Int)

enum class ScanFailure {
    /** No Bluetooth LE on this device. */
    UNSUPPORTED,

    /** See [SensorPermissionPolicy.scanPermissions]. */
    NO_PERMISSION,

    /** 30 and below: the location switch is off, a scan would find nothing. */
    LOCATION_OFF,
    BLUETOOTH_OFF,

    /** Five scans started in the last 30 s; [ScanException.waitMs] until the next one may. */
    TOO_FREQUENT,

    /** The system refused or stopped the scan ([ScanException.errorCode]: `SCAN_FAILED_*`). */
    FAILED
}

class ScanException(
    val reason: ScanFailure,
    val waitMs: Long = 0L,
    val errorCode: Int? = null
) : Exception("scan failed: $reason" + (errorCode?.let { " ($it)" } ?: ""))

/** The outcome of [SensorScanner.probe]. */
sealed interface ProbeResult {
    /** A working heart-rate sensor: pair it. [name] as the stack knows it; the scan's may be better. */
    data class Ok(val name: String?, val batteryPercent: Int?) : ProbeResult
    data class Failed(val reason: ProbeFailure) : ProbeResult
}

enum class ProbeFailure {
    /** `BLUETOOTH_CONNECT` (31+) is missing. */
    NO_PERMISSION,
    BLUETOOTH_OFF,

    /** No connection or setup in time: out of range, asleep, or busy with another phone/watch. */
    UNREACHABLE,

    /** Connected, but there is no Heart Rate Service. */
    NOT_HEART_RATE_SENSOR
}

/**
 * Android counts scan starts per app: when the last [MAX_STARTS] all fall within [WINDOW_MS],
 * a new start is silently ignored.
 */
object ScanStartLimit {
    const val MAX_STARTS = 5
    const val WINDOW_MS = 30_000L

    /** How long from [nowMs] until a start is allowed after [starts] (oldest first); 0: now. */
    fun waitMs(starts: List<Long>, nowMs: Long): Long {
        if (starts.size < MAX_STARTS) return 0L
        val oldestCounted = starts[starts.size - MAX_STARTS]
        return (oldestCounted + WINDOW_MS - nowMs).coerceAtLeast(0L)
    }

    /** [starts] with a start at [nowMs], trimmed to the ones that can still matter. */
    fun record(starts: List<Long>, nowMs: Long): List<Long> = (starts + nowMs).takeLast(MAX_STARTS)
}

/**
 * The list with [found] in it: a device already there keeps its place (rows do not jump under
 * the finger) and its name when the new advertisement has none; the signal is the newest.
 */
fun List<FoundSensor>.withFound(found: FoundSensor): List<FoundSensor> {
    val at = indexOfFirst { it.address == found.address }
    if (at < 0) return this + found
    val known = this[at]
    return toMutableList().also { it[at] = found.copy(name = found.name ?: known.name) }
}
