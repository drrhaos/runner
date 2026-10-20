package com.runner.academy.util

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.runner.academy.data.Lap

/**
 * [com.runner.academy.data.Workout.lapsJson]: an array of laps. Manual JsonObject read/write
 * (like [IntervalSegmentsJson]) so release R8 cannot rename the keys.
 */
object LapsJson {

    fun toJson(laps: List<Lap>): String? {
        if (laps.isEmpty()) return null
        val array = JsonArray()
        laps.forEach { lap ->
            array.add(
                JsonObject().apply {
                    addProperty("start", lap.start)
                    lap.timerMs?.let { addProperty("timerMs", it) }
                    lap.distanceM?.let { addProperty("distanceM", it) }
                    lap.avgHr?.let { addProperty("avgHr", it) }
                    lap.maxHr?.let { addProperty("maxHr", it) }
                    lap.avgCadence?.let { addProperty("avgCadence", it) }
                }
            )
        }
        return array.toString()
    }

    /** The laps of [json]; none when it is missing or unreadable, a lap without a start skipped. */
    fun parse(json: String?): List<Lap> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val element = JsonParser.parseString(json)
            if (!element.isJsonArray) return emptyList()
            element.asJsonArray.mapNotNull { el ->
                if (!el.isJsonObject) return@mapNotNull null
                val lap = el.asJsonObject
                Lap(
                    start = lap.number("start")?.toLong() ?: return@mapNotNull null,
                    timerMs = lap.number("timerMs")?.toLong(),
                    distanceM = lap.number("distanceM")?.toFloat(),
                    avgHr = lap.number("avgHr")?.toInt(),
                    maxHr = lap.number("maxHr")?.toInt(),
                    avgCadence = lap.number("avgCadence")?.toFloat()
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun JsonObject.number(key: String): Number? {
        val el: JsonElement = get(key) ?: return null
        return if (el.isJsonPrimitive && el.asJsonPrimitive.isNumber) el.asNumber else null
    }
}
