package com.runner.academy.ui.devices

import androidx.annotation.StringRes
import com.runner.academy.R
import com.runner.academy.sensor.HeartRateSample
import com.runner.academy.sensor.PairedSensor
import com.runner.academy.sensor.SensorLink
import com.runner.academy.sensor.SensorStatus

/** The colour of a device's status line; the state is always in its words too. */
enum class DeviceTone { NORMAL, PRIMARY, ERROR }

/** The button under a device's text, when something blocks it. */
enum class DeviceAction { ENABLE_BLUETOOTH, ALLOW }

/**
 * What a paired device's row shows.
 *
 * @param name the device's name, null when it has none (shown as "Unnamed").
 * @param status the link in words; announced when it changes.
 * @param bpm the pulse after the status ("· 142 bpm"), only while connected with contact.
 * @param batteryPercent the battery in the meta line, null: not shown.
 * @param lastSeenAt wall-clock time of the last contact in the meta line, null: not shown.
 */
data class DeviceRow(
    val name: String?,
    @StringRes val status: Int,
    val bpm: Int?,
    val tone: DeviceTone,
    val batteryPercent: Int?,
    val lastSeenAt: Long?,
    val action: DeviceAction?
) {
    /** The meta line turns red: the strap will die soon. */
    val batteryLow: Boolean
        get() = batteryPercent != null && batteryPercent <= DeviceRowText.LOW_BATTERY_PERCENT
}

/** The texts of a device row on the Devices screen. */
object DeviceRowText {

    /** "Searching…" turns into "offline" after this long without a connection. */
    const val OFFLINE_AFTER_MS = 30_000L

    const val LOW_BATTERY_PERCENT = 15

    /**
     * @param lastSample the newest sample since the screen opened or the link last changed.
     * @param nowMs and [linkSinceMs]: one monotonic clock; [linkSinceMs] is when the screen
     *   opened or the link last changed, whichever is later.
     */
    fun of(
        paired: PairedSensor,
        status: SensorStatus,
        lastSample: HeartRateSample?,
        nowMs: Long,
        linkSinceMs: Long
    ): DeviceRow {
        val name = paired.name?.takeIf { it.isNotBlank() }
        val battery = status.batteryPercent ?: paired.batteryPercent
        val lastSeen = status.lastSeenAt ?: paired.lastSeenAt
        fun row(
            @StringRes text: Int,
            tone: DeviceTone = DeviceTone.NORMAL,
            bpm: Int? = null,
            battery: Int? = null,
            lastSeen: Long? = null,
            action: DeviceAction? = null
        ) = DeviceRow(name, text, bpm, tone, battery, lastSeen, action)

        return when (status.link) {
            SensorLink.CONNECTED -> when {
                lastSample == null -> row(R.string.devices_status_connected, DeviceTone.PRIMARY, battery = battery)
                lastSample.bpm == null -> row(R.string.devices_status_no_contact, DeviceTone.ERROR, battery = battery)
                else -> row(R.string.devices_status_connected, DeviceTone.PRIMARY, bpm = lastSample.bpm, battery = battery)
            }
            SensorLink.BLUETOOTH_OFF ->
                row(R.string.devices_status_bluetooth_off, DeviceTone.ERROR, action = DeviceAction.ENABLE_BLUETOOTH)
            SensorLink.NO_PERMISSION ->
                row(R.string.devices_status_no_permission, DeviceTone.ERROR, action = DeviceAction.ALLOW)
            SensorLink.CONNECTING, SensorLink.SEARCHING, SensorLink.IDLE -> {
                val stillSearching = status.link != SensorLink.IDLE && nowMs - linkSinceMs < OFFLINE_AFTER_MS
                when {
                    stillSearching -> row(R.string.devices_status_searching, battery = battery)
                    lastSeen != null -> row(R.string.devices_status_offline, lastSeen = lastSeen)
                    else -> row(R.string.devices_status_never_seen)
                }
            }
        }
    }
}
