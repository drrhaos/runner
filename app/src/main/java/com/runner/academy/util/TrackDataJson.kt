package com.runner.academy.util

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.runner.academy.data.LocationSource
import com.runner.academy.data.TrackData
import com.runner.academy.data.TrackPoint
import java.lang.reflect.Type

/**
 * Safe TrackData JSON parse/serialize.
 *
 * Backups from older apps (e.g. com.example.runner / export-all-workouts-example)
 * omit [TrackPoint.afterGap] and [TrackPoint.source]. Plain Gson leaves Kotlin
 * non-null Booleans as null → crashes like NPE on boolean access.
 */
object TrackDataJson {

    private val gson: Gson = GsonBuilder()
        .registerTypeAdapter(TrackPoint::class.java, TrackPointDeserializer())
        .create()

    fun parse(json: String?): TrackData? {
        if (json.isNullOrBlank()) return null
        return try {
            gson.fromJson(json, TrackData::class.java)?.let { normalize(it) }
        } catch (_: OutOfMemoryError) {
            null
        } catch (_: Exception) {
            null
        }
    }

    fun toJson(trackData: TrackData): String = gson.toJson(normalize(trackData))

    /**
     * Re-encodes stored track JSON with current defaults so Room rows from old
     * backups are safe for cleaners / maps / charts.
     */
    fun normalizeStored(json: String?): String? {
        val parsed = parse(json) ?: return json?.takeIf { it.isNotBlank() }
        return toJson(parsed)
    }

    private fun normalize(data: TrackData): TrackData {
        val points = data.points.mapNotNull { point ->
            if (!GpsFilter.isValidLatLon(point.latitude, point.longitude)) {
                null
            } else {
                point.copy(
                    afterGap = point.afterGap,
                    source = point.source.ifBlank { LocationSource.GPS.name }
                )
            }
        }
        return data.copy(points = points)
    }

    private class TrackPointDeserializer : JsonDeserializer<TrackPoint> {
        override fun deserialize(
            json: JsonElement?,
            typeOfT: Type?,
            context: JsonDeserializationContext?
        ): TrackPoint {
            if (json == null || json is JsonNull || !json.isJsonObject) {
                throw JsonParseException("TrackPoint must be a JSON object")
            }
            val obj = json.asJsonObject
            return TrackPoint(
                latitude = obj.number("latitude")?.toDouble()
                    ?: throw JsonParseException("TrackPoint missing latitude"),
                longitude = obj.number("longitude")?.toDouble()
                    ?: throw JsonParseException("TrackPoint missing longitude"),
                timestamp = obj.number("timestamp")?.toLong() ?: 0L,
                accuracy = obj.number("accuracy")?.toFloat(),
                speed = obj.number("speed")?.toFloat(),
                altitude = obj.number("altitude")?.toDouble(),
                afterGap = obj.booleanOr("after_gap", false),
                source = obj.stringOr("source", LocationSource.GPS.name)
            )
        }

        private fun JsonObject.number(key: String): Number? {
            val el = get(key) ?: return null
            if (el.isJsonNull || !el.isJsonPrimitive) return null
            return try {
                el.asNumber
            } catch (_: Exception) {
                el.asString.toDoubleOrNull()
            }
        }

        private fun JsonObject.booleanOr(key: String, default: Boolean): Boolean {
            val el = get(key) ?: return default
            if (el.isJsonNull) return default
            return try {
                when {
                    el.isJsonPrimitive && el.asJsonPrimitive.isBoolean -> el.asBoolean
                    el.isJsonPrimitive && el.asJsonPrimitive.isNumber -> el.asInt != 0
                    el.isJsonPrimitive && el.asJsonPrimitive.isString ->
                        when (el.asString.trim().lowercase()) {
                            "true", "1", "yes" -> true
                            "false", "0", "no" -> false
                            else -> default
                        }
                    else -> default
                }
            } catch (_: Exception) {
                default
            }
        }

        private fun JsonObject.stringOr(key: String, default: String): String {
            val el = get(key) ?: return default
            if (el.isJsonNull) return default
            return try {
                if (el.isJsonPrimitive) el.asString.ifBlank { default } else default
            } catch (_: Exception) {
                default
            }
        }
    }
}
