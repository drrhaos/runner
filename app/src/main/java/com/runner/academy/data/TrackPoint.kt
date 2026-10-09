package com.runner.academy.data

import com.google.gson.TypeAdapter
import com.google.gson.annotations.JsonAdapter
import com.google.gson.annotations.SerializedName
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter

data class TrackPoint(
    @SerializedName("latitude")
    val latitude: Double,
    @SerializedName("longitude")
    val longitude: Double,
    @SerializedName("timestamp")
    val timestamp: Long, // время в миллисекундах
    @SerializedName("accuracy")
    val accuracy: Float?, // точность GPS в метрах
    @SerializedName("speed")
    val speed: Float?, // скорость в м/с
    @SerializedName("altitude")
    val altitude: Double?, // высота над уровнем моря
    /**
     * True when this point resumes the track after a break: the line is not drawn solid from the
     * previous point. Without [bridgeMeters] it is a GPS gap and no distance is counted.
     */
    @SerializedName("after_gap")
    val afterGap: Boolean = false,
    /** [LocationSource.PEDOMETER] on a bridge point whose [bridgeMeters] came from steps. */
    @SerializedName("source")
    val source: String = LocationSource.GPS.name,
    /**
     * Set on an [afterGap] point that closes a dropped (false-signal) stretch: the distance
     * counted from the previous point (straight line or steps × stride, whichever is longer).
     * The step is drawn dashed. Optional: older tracks have none.
     */
    @SerializedName("bridge_m")
    val bridgeMeters: Float? = null,
    /** Steps since the workout started, at this fix; null without a step sensor. */
    @SerializedName("steps")
    val steps: Int? = null,
    /** Steps per minute around this fix; null without a step sensor. */
    @SerializedName("cadence")
    val cadence: Float? = null,
    /**
     * On the last point only: step distance counted after it that no fix closed (the run was
     * stopped during a silence or a false signal). Counted in the total, not drawn.
     */
    @SerializedName("tail_m")
    val tailMeters: Float? = null,
    /**
     * Raw points only: the first fix after the run resumed from a pause, so the save path knows
     * the straight line from the previous point crosses the pause (not run).
     */
    @SerializedName("after_pause")
    val afterPause: Boolean? = null,
    /**
     * Barometric altitude at this fix's moment (standard atmosphere, uncalibrated: only its
     * differences mean something); null without a barometer and on older tracks.
     */
    @SerializedName("baro_m")
    val baroM: Float? = null
)

/**
 * The altitude of this point, or null when it has none. Exactly 0.0 is "none" too: older
 * tracks stored a missing altitude as 0.0, and a real ellipsoid height is never exactly zero.
 */
fun TrackPoint.knownAltitude(): Double? = altitude?.takeIf { it.isFinite() && it != 0.0 }

/** Some point has a [knownAltitude]: the track has elevation at all. */
fun List<TrackPoint>.hasAltitude(): Boolean = any { it.knownAltitude() != null }

/** The barometric altitude of this point ([TrackPoint.baroM]), null when it has none. */
fun TrackPoint.knownBaroAltitude(): Double? = baroM?.takeIf { it.isFinite() }?.toDouble()

/** Share of points with a [knownBaroAltitude] that makes the track's elevation barometric. */
const val BAROMETER_MIN_SHARE = 0.8

/**
 * At least [BAROMETER_MIN_SHARE] of the points have a [knownBaroAltitude]: the barometer worked
 * through the run (a few fixes without readings around them do not change that).
 */
fun List<TrackPoint>.isBarometric(): Boolean =
    isNotEmpty() && count { it.knownBaroAltitude() != null } >= BAROMETER_MIN_SHARE * size

data class TrackData(
    @SerializedName("points")
    val points: List<TrackPoint>,
    @SerializedName("total_distance")
    val totalDistance: Float, // общая дистанция в метрах
    @SerializedName("total_duration")
    val totalDuration: Long, // общая продолжительность в миллисекундах
    @SerializedName("avg_speed")
    val avgSpeed: Float, // средняя скорость в м/с
    @SerializedName("max_speed")
    val maxSpeed: Float, // максимальная скорость в м/с
    @SerializedName("start_time")
    val startTime: Long, // время начала тренировки
    @SerializedName("end_time")
    val endTime: Long?, // время окончания тренировки
    /**
     * Pause intervals of the session (wall clock). Null on older tracks and on routes whose
     * time was rebuilt: "unknown, assume no pauses". Every builder of a track carries it over.
     */
    @SerializedName("pauses")
    val pauses: List<PauseInterval>? = null,
    /** True when the point times were made up (GPX without `<time>`, route time from the form). */
    @SerializedName("time_synthetic")
    val timeSynthetic: Boolean? = null,
    /** Where the point altitudes come from, when the writer knew it; null on older tracks. */
    @SerializedName("elevation_source")
    val elevationSource: ElevationSource? = null
)

/**
 * A pause of the session, [start]..[end] in wall-clock ms.
 * Every field has a default so Gson builds it through the no-arg constructor.
 */
data class PauseInterval(
    @SerializedName("start")
    val start: Long = 0L,
    @SerializedName("end")
    val end: Long = 0L,
    @SerializedName("kind")
    val kind: PauseKind = PauseKind.AUTO
)

/** Who paused: the runner or auto-pause. Stored by name; a missing or unknown name reads as [AUTO]. */
@JsonAdapter(PauseKindAdapter::class, nullSafe = false)
enum class PauseKind { MANUAL, AUTO }

/** Gson's own enum adapter reads an unknown name as null, which a non-null [PauseInterval.kind] cannot hold. */
class PauseKindAdapter : TypeAdapter<PauseKind>() {
    override fun write(out: JsonWriter, value: PauseKind?) {
        if (value == null) out.nullValue() else out.value(value.name)
    }

    override fun read(reader: JsonReader): PauseKind {
        if (reader.peek() == JsonToken.NULL) {
            reader.nextNull()
            return PauseKind.AUTO
        }
        val name = reader.nextString()
        return PauseKind.entries.find { it.name == name } ?: PauseKind.AUTO
    }
}
