package com.runner.academy.sensor

/** A kind of external sensor; one paired device per type. */
enum class SensorType { HEART_RATE }

/** How a sensor is reachable right now. */
enum class SensorLink {
    /** `BLUETOOTH_CONNECT` (31+) is not granted: nothing can be tried. */
    NO_PERMISSION,
    BLUETOOTH_OFF,
    /** Not wanted (no lease or no paired device) or given up (not a heart-rate sensor). */
    IDLE,
    /** A direct connection attempt, its setup, or the pause before the next one. */
    CONNECTING,
    CONNECTED,
    /** The slow background attempt after the direct ones failed (out of range). */
    SEARCHING
}

/**
 * What screens and the service show about a sensor.
 *
 * @param lastSeenAt wall-clock time of the last data from the device, null if never seen.
 */
data class SensorStatus(
    val link: SensorLink,
    val batteryPercent: Int? = null,
    val lastSeenAt: Long? = null
)

/**
 * One heart-rate reading at [elapsedNanos] (`SystemClock.elapsedRealtimeNanos`, the clock of
 * location fixes). [bpm] is null when there is no usable value: no skin contact, 0 or an
 * implausible number (see [HeartRateMeasurement.toSample]).
 */
data class HeartRateSample(val elapsedNanos: Long, val bpm: Int?)

/**
 * A sensor-link event for the diagnostics log (`{"sensor":"hr","event":..,"status":..}`).
 *
 * @param value the GATT status for [Kind.DISCONNECTED]/[Kind.GATT_ERROR], percent for
 *   [Kind.BATTERY], dBm for [Kind.RSSI], else null.
 */
data class SensorDiagEvent(val kind: Kind, val value: Int? = null) {

    enum class Kind(val wireName: String) {
        CONNECTING("connecting"),
        CONNECTED("connected"),
        DISCONNECTED("disconnected"),
        GATT_ERROR("gatt_error"),
        AUTO_CONNECT("auto_connect"),
        BT_OFF("bt_off"),
        BT_ON("bt_on"),
        BATTERY("battery"),
        SILENCE("silence"),
        RSSI("rssi")
    }
}
