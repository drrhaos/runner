package com.runner.academy.util

import java.util.Date

/**
 * Пересобирает трек тренировки при сохранении из формы добавления/редактирования,
 * чтобы время точек соответствовало именно этой тренировке (графики, отрезки, GPX).
 */
object WorkoutTrackRebuilder {

    enum class TimeSource {
        /** Трек не менялся (или маршрута нет). */
        UNCHANGED,
        /** Трек не менялся, время сдвинуто на смену даты. */
        SHIFTED,
        /** Новый маршрут, время перенесено с записанного трека тренировки. */
        RECORDED,
        /** Новый маршрут, время распределено равномерно по дистанции. */
        UNIFORM
    }

    data class Result(val trackDataJson: String?, val timeSource: TimeSource)

    /**
     * @param originalTrackJson трек тренировки до редактирования (null для новой)
     * @param selectedTrackJson трек, выбранный в форме
     * @param originalDate дата тренировки до редактирования (null для новой)
     * @param newDate дата из формы
     * @param durationMs длительность из формы — для равномерного распределения
     */
    fun rebuild(
        originalTrackJson: String?,
        selectedTrackJson: String?,
        originalDate: Date?,
        newDate: Date,
        durationMs: Long
    ): Result {
        if (selectedTrackJson.isNullOrBlank()) return Result(null, TimeSource.UNCHANGED)
        val dateShiftMs = originalDate?.let { newDate.time - it.time } ?: 0L

        if (selectedTrackJson == originalTrackJson) {
            if (dateShiftMs == 0L) return Result(selectedTrackJson, TimeSource.UNCHANGED)
            val track = TrackDataJson.parse(selectedTrackJson)
                ?: return Result(selectedTrackJson, TimeSource.UNCHANGED)
            return Result(
                TrackDataJson.toJson(RouteTimeAligner.shiftTime(track, dateShiftMs)),
                TimeSource.SHIFTED
            )
        }

        val route = TrackDataJson.parse(selectedTrackJson)
            ?: return Result(selectedTrackJson, TimeSource.UNCHANGED)

        val recorded = TrackDataJson.parse(originalTrackJson)
        val aligned = recorded?.let { RouteTimeAligner.alignToRecorded(route, it) }
        // Both branches give the route the form's time, not a recording's: not a record candidate
        if (aligned != null) {
            return Result(
                TrackDataJson.toJson(RouteTimeAligner.shiftTime(aligned, dateShiftMs).copy(timeSynthetic = true)),
                TimeSource.RECORDED
            )
        }

        val uniform = RouteTimeAligner.distributeByDistance(route, newDate.time, durationMs)
            ?: return Result(selectedTrackJson, TimeSource.UNCHANGED)
        return Result(TrackDataJson.toJson(uniform.copy(timeSynthetic = true)), TimeSource.UNIFORM)
    }
}
