package com.runner.academy.util

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * GPS diagnostics file format: JSON Lines, one record per line, so a crash only loses the
 * last half-written line. Records:
 *  - `header` — format version, recording start, app version, Android SDK;
 *  - `fix` — every raw fix from the provider (before filtering) with the filter's verdict;
 *  - `gnss` — satellites per system (visible / used in fix / C/N0), to spot spoofing and jamming;
 *  - `event` — pause, resume, screen on/off and similar session events;
 *  - `power` — battery saver state and its location mode ([PowerSaveCheck.locationModeName]),
 *    at the start of the recording and on every change, since it can cut GPS with the screen off.
 *
 * Times: `t` is elapsedRealtime in ms (monotonic, comparable across record types);
 * `time` on a fix is the provider's wall-clock time.
 *
 * Readers skip unknown record types, so adding a type keeps [FORMAT_VERSION].
 */
object GpsDiagnostics {

    const val FORMAT_VERSION = 1

    // Values of android.location.GnssStatus.CONSTELLATION_* (kept here for JVM tests)
    const val CONSTELLATION_GPS = 1
    const val CONSTELLATION_SBAS = 2
    const val CONSTELLATION_GLONASS = 3
    const val CONSTELLATION_QZSS = 4
    const val CONSTELLATION_BEIDOU = 5
    const val CONSTELLATION_GALILEO = 6
    const val CONSTELLATION_IRNSS = 7

    fun constellationName(type: Int): String = when (type) {
        CONSTELLATION_GPS -> "GPS"
        CONSTELLATION_SBAS -> "SBAS"
        CONSTELLATION_GLONASS -> "GLONASS"
        CONSTELLATION_QZSS -> "QZSS"
        CONSTELLATION_BEIDOU -> "BEIDOU"
        CONSTELLATION_GALILEO -> "GALILEO"
        CONSTELLATION_IRNSS -> "IRNSS"
        else -> "UNKNOWN"
    }

    data class DiagFix(
        val elapsedMs: Long,
        val timeMs: Long,
        val latitude: Double,
        val longitude: Double,
        val accuracy: Float?,
        val speed: Float?,
        val bearing: Float?,
        val altitude: Double?,
        val provider: String,
        val isMock: Boolean
    )

    /** Session events; [wire] is the name stored in the file. */
    enum class Event(val wire: String) {
        START("start"),
        /** Recording continued after the process was killed and the workout restored. */
        RESTORED("restored"),
        PAUSE("pause"),
        RESUME("resume"),
        SCREEN_ON("screen_on"),
        SCREEN_OFF("screen_off"),
        STOP("stop"),
        /** The system destroyed the service mid-workout; a RESTORED usually follows. */
        SERVICE_DESTROYED("service_destroyed"),
        /**
         * A restore could not bring the service back to the foreground (refused from the
         * background); tracking stopped until the user opens the app.
         */
        RESTORE_FAILED("restore_failed"),
        SIZE_LIMIT("size_limit")
    }

    /** What the live filter did with a fix (or that nothing processed it, e.g. while paused). */
    enum class FixResult { ACCEPTED, NEAR_DUPLICATE, REJECTED, NOT_PROCESSED }

    data class DiagSatellite(val constellation: Int, val cn0DbHz: Float, val usedInFix: Boolean)

    data class SystemStats(val visible: Int, val used: Int, val meanCn0: Float, val maxCn0: Float)

    data class GnssSummary(val systems: Map<String, SystemStats>) {
        val visible: Int get() = systems.values.sumOf { it.visible }
        val used: Int get() = systems.values.sumOf { it.used }

        companion object {
            fun of(satellites: List<DiagSatellite>): GnssSummary = GnssSummary(
                satellites.groupBy { constellationName(it.constellation) }
                    .mapValues { (_, sats) ->
                        SystemStats(
                            visible = sats.size,
                            used = sats.count { it.usedInFix },
                            meanCn0 = sats.map { it.cn0DbHz }.average().toFloat(),
                            maxCn0 = sats.maxOf { it.cn0DbHz }
                        )
                    }
                    .toSortedMap()
            )
        }
    }

    sealed class DiagRecord {
        data class Header(
            val formatVersion: Int,
            val startTimeMs: Long,
            val appVersion: String,
            val sdkInt: Int
        ) : DiagRecord()

        data class Fix(val fix: DiagFix, val result: FixResult) : DiagRecord()
        data class Gnss(val elapsedMs: Long, val summary: GnssSummary) : DiagRecord()
        /** [name] stays a string so files with newer event names still parse. */
        data class Event(val elapsedMs: Long, val name: String) : DiagRecord()
        /** [locationMode] is the wire name from [PowerSaveCheck.locationModeName]. */
        data class Power(val elapsedMs: Long, val powerSaveMode: Boolean, val locationMode: String) : DiagRecord()
    }

    fun headerLine(startTimeMs: Long, appVersion: String, sdkInt: Int): String = JsonObject().apply {
        addProperty("type", "header")
        addProperty("format", FORMAT_VERSION)
        addProperty("start", startTimeMs)
        addProperty("app", appVersion)
        addProperty("sdk", sdkInt)
    }.toString()

    fun fixLine(fix: DiagFix, result: FixResult): String = JsonObject().apply {
        addProperty("type", "fix")
        addProperty("t", fix.elapsedMs)
        addProperty("time", fix.timeMs)
        addProperty("lat", fix.latitude)
        addProperty("lon", fix.longitude)
        fix.accuracy?.let { addProperty("acc", it) }
        fix.speed?.let { addProperty("speed", it) }
        fix.bearing?.let { addProperty("bearing", it) }
        fix.altitude?.let { addProperty("alt", it) }
        addProperty("provider", fix.provider)
        if (fix.isMock) addProperty("mock", true)
        addProperty("result", result.name)
    }.toString()

    fun gnssLine(elapsedMs: Long, summary: GnssSummary): String = JsonObject().apply {
        addProperty("type", "gnss")
        addProperty("t", elapsedMs)
        add("systems", JsonObject().apply {
            summary.systems.forEach { (name, stats) ->
                add(name, JsonObject().apply {
                    addProperty("visible", stats.visible)
                    addProperty("used", stats.used)
                    addProperty("meanCn0", stats.meanCn0)
                    addProperty("maxCn0", stats.maxCn0)
                })
            }
        })
    }.toString()

    fun eventLine(elapsedMs: Long, event: Event): String = JsonObject().apply {
        addProperty("type", "event")
        addProperty("t", elapsedMs)
        addProperty("name", event.wire)
    }.toString()

    fun powerLine(elapsedMs: Long, status: PowerSaveCheck.Status): String = JsonObject().apply {
        addProperty("type", "power")
        addProperty("t", elapsedMs)
        addProperty("saver", status.powerSaveMode)
        addProperty("location", PowerSaveCheck.locationModeName(status.locationMode))
    }.toString()

    /** Reads a diagnostics file back (e.g. to turn a shared file into a replay fixture). */
    fun parse(lines: List<String>): List<DiagRecord> = lines.mapNotNull { line ->
        if (line.isBlank()) return@mapNotNull null
        try {
            parseRecord(JsonParser.parseString(line).asJsonObject)
        } catch (_: Exception) {
            null
        }
    }

    private fun parseRecord(o: JsonObject): DiagRecord? = when (o.get("type")?.asString) {
        "header" -> DiagRecord.Header(
            formatVersion = o.get("format").asInt,
            startTimeMs = o.get("start").asLong,
            appVersion = o.get("app").asString,
            sdkInt = o.get("sdk").asInt
        )
        "fix" -> DiagRecord.Fix(
            fix = DiagFix(
                elapsedMs = o.get("t").asLong,
                timeMs = o.get("time").asLong,
                latitude = o.get("lat").asDouble,
                longitude = o.get("lon").asDouble,
                accuracy = o.get("acc")?.asFloat,
                speed = o.get("speed")?.asFloat,
                bearing = o.get("bearing")?.asFloat,
                altitude = o.get("alt")?.asDouble,
                provider = o.get("provider").asString,
                isMock = o.get("mock")?.asBoolean ?: false
            ),
            result = FixResult.valueOf(o.get("result").asString)
        )
        "gnss" -> DiagRecord.Gnss(
            elapsedMs = o.get("t").asLong,
            summary = GnssSummary(
                o.getAsJsonObject("systems").entrySet().associate { (name, el) ->
                    val s = el.asJsonObject
                    name to SystemStats(
                        visible = s.get("visible").asInt,
                        used = s.get("used").asInt,
                        meanCn0 = s.get("meanCn0").asFloat,
                        maxCn0 = s.get("maxCn0").asFloat
                    )
                }.toSortedMap()
            )
        )
        "event" -> DiagRecord.Event(o.get("t").asLong, o.get("name").asString)
        "power" -> DiagRecord.Power(
            elapsedMs = o.get("t").asLong,
            powerSaveMode = o.get("saver").asBoolean,
            locationMode = o.get("location").asString
        )
        else -> null
    }
}
