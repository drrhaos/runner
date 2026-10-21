package com.runner.academy.sensor

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * A device the user paired for [type].
 *
 * @param name the advertised name, null when the device did not send one.
 * @param pairedAt wall-clock time of pairing.
 * @param lastSeenAt wall-clock time of the last data from the device, null until data first came.
 */
data class PairedSensor(
    val type: SensorType,
    val address: String,
    val name: String?,
    val pairedAt: Long,
    val lastSeenAt: Long?,
    val batteryPercent: Int?
)

/**
 * The paired sensors, one per [SensorType]: [put] replaces the device of its type. Kept in its
 * own preferences file, one JSON object per type under the type's name, written by hand (not
 * Gson reflection) so release R8 cannot rename the keys.
 *
 * One instance per process ([changes] only sees writes made through it).
 */
class PairedSensorStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _changes = MutableStateFlow(load())

    /** Every paired sensor by type; updated on each [put] and [remove]. */
    val changes: StateFlow<Map<SensorType, PairedSensor>> = _changes.asStateFlow()

    fun get(type: SensorType): PairedSensor? = _changes.value[type]

    /** Writes go to the file and the flow under one lock: they land in the same order in both. */
    private val lock = Any()

    fun put(sensor: PairedSensor) {
        val json = toJson(sensor)
        synchronized(lock) {
            prefs.edit { putString(sensor.type.name, json) }
            _changes.update { it + (sensor.type to sensor) }
        }
    }

    /**
     * Rewrites the device of [type] with [change], only while it is still the one at [address]:
     * a write from the radio (battery, last seen) never brings back a device the user just
     * replaced or forgot.
     */
    fun update(type: SensorType, address: String, change: (PairedSensor) -> PairedSensor) {
        synchronized(lock) {
            val current = _changes.value[type]?.takeIf { it.address == address } ?: return
            val updated = change(current).copy(type = type, address = address)
            prefs.edit { putString(type.name, toJson(updated)) }
            _changes.update { it + (type to updated) }
        }
    }

    fun remove(type: SensorType) {
        synchronized(lock) {
            prefs.edit { remove(type.name) }
            _changes.update { it - type }
        }
    }

    private fun load(): Map<SensorType, PairedSensor> =
        SensorType.entries.mapNotNull { type ->
            prefs.getString(type.name, null)?.let { parse(type, it) }?.let { type to it }
        }.toMap()

    companion object {
        const val PREFS_NAME = "sensors"

        private const val KEY_ADDRESS = "address"
        private const val KEY_NAME = "name"
        private const val KEY_PAIRED_AT = "pairedAt"
        private const val KEY_LAST_SEEN_AT = "lastSeenAt"
        private const val KEY_BATTERY = "batteryPercent"

        private fun toJson(sensor: PairedSensor): String = JsonObject().apply {
            addProperty(KEY_ADDRESS, sensor.address)
            sensor.name?.let { addProperty(KEY_NAME, it) }
            addProperty(KEY_PAIRED_AT, sensor.pairedAt)
            sensor.lastSeenAt?.let { addProperty(KEY_LAST_SEEN_AT, it) }
            sensor.batteryPercent?.let { addProperty(KEY_BATTERY, it) }
        }.toString()

        /** The stored entry, or null when it is not readable or has no address. */
        private fun parse(type: SensorType, json: String): PairedSensor? = try {
            val obj = JsonParser.parseString(json).asJsonObject
            fun JsonObject.value(key: String) = get(key)?.takeUnless { it.isJsonNull }
            obj.value(KEY_ADDRESS)?.asString?.let { address ->
                PairedSensor(
                    type = type,
                    address = address,
                    name = obj.value(KEY_NAME)?.asString,
                    pairedAt = obj.value(KEY_PAIRED_AT)?.asLong ?: 0L,
                    lastSeenAt = obj.value(KEY_LAST_SEEN_AT)?.asLong,
                    batteryPercent = obj.value(KEY_BATTERY)?.asInt
                )
            }
        } catch (_: Exception) {
            null
        }
    }
}
