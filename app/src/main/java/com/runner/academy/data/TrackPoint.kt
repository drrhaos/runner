package com.runner.academy.data

import com.google.gson.annotations.SerializedName

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
    val afterPause: Boolean? = null
)

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
    val endTime: Long? // время окончания тренировки
)
