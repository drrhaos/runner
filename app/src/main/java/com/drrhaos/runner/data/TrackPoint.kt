package com.drrhaos.runner.data

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
    /** True when this point resumes the track after a GPS gap — do not count distance to previous. */
    @SerializedName("after_gap")
    val afterGap: Boolean = false,
    @SerializedName("source")
    val source: String = LocationSource.GPS.name
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
