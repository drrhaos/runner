package com.runner.academy.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.runner.academy.R
import java.util.Date

@Entity(tableName = "workouts")
data class Workout(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val date: Date,
    val distance: Float, // в километрах
    val duration: Long, // в миллисекундах, без ручных пауз
    /**
     * [duration] minus auto-pause intervals, always ≤ [duration]. No Kotlin default on purpose:
     * every path that builds a workout must decide it explicitly (a forgotten field would
     * silently become 0 and break the pace).
     */
    @ColumnInfo(defaultValue = "0")
    val movingDuration: Long,
    val avgPace: Float, // темп по движению: movingDuration / distance, минуты на километр
    val calories: Int?,
    val notes: String?,
    val type: WorkoutType,
    val trackData: String? = null, // JSON с траекторией и временными метками
    val isFavorite: Boolean = false,
    /** JSON snapshot of template intervals used during this workout (for charts). */
    val intervalSegmentsJson: String? = null,
    // Derived from the track by the metrics pass; null until computed or when there is no data
    val elevationGain: Float? = null, // метры
    val elevationLoss: Float? = null, // метры
    val elevationSource: ElevationSource? = null,
    val avgCadence: Float? = null, // шагов в минуту
    /** Encoded route preview for the list, so pages do not load the track JSON. */
    val routePreview: String? = null,
    /** User flag: the workout never counts towards personal records. */
    @ColumnInfo(defaultValue = "0")
    val excludeFromRecords: Boolean = false,
    // Reserved for a heart-rate sensor: only carried through backups, no UI yet
    val avgHeartRate: Int? = null,
    val maxHeartRate: Int? = null,
    /**
     * Version of the algorithms the derived columns were computed with; 0 = not computed yet
     * (tells "not computed" apart from "no data" and reruns the pass when an algorithm changes).
     */
    @ColumnInfo(defaultValue = "0")
    val metricsVersion: Int = 0
)

/** Where [Workout.elevationGain] comes from; stored by name. */
enum class ElevationSource {
    BAROMETER,
    GPS,
    FILE,
    NONE;

    companion object {
        /**
         * The source of [track]'s altitudes, derived from the track alone (so the column can
         * always be recomputed): what the writer declared when the track has that data, else
         * the barometer when ≥ 80 % of the points carry a pressure altitude ([isBarometric]),
         * else GPS when some point has a known altitude ([knownAltitude]; tracks recorded
         * before the source was stored), else none.
         */
        fun of(track: TrackData): ElevationSource {
            val barometric = track.points.isBarometric()
            val withAltitude = track.points.hasAltitude()
            when (track.elevationSource) {
                BAROMETER -> if (barometric) return BAROMETER
                GPS, FILE -> if (withAltitude) return track.elevationSource
                NONE, null -> Unit
            }
            return when {
                barometric -> BAROMETER
                withAltitude -> GPS
                else -> NONE
            }
        }
    }
}

enum class WorkoutType {
    EASY_RUN,
    TEMPO_RUN,
    INTERVAL_TRAINING,
    LONG_RUN,
    RECOVERY_RUN,
    RACE
}

fun WorkoutType.displayName(context: Context): String = when (this) {
    WorkoutType.EASY_RUN -> context.getString(R.string.workout_type_easy_run)
    WorkoutType.TEMPO_RUN -> context.getString(R.string.workout_type_tempo_run)
    WorkoutType.INTERVAL_TRAINING -> context.getString(R.string.workout_type_interval_training)
    WorkoutType.LONG_RUN -> context.getString(R.string.workout_type_long_run)
    WorkoutType.RECOVERY_RUN -> context.getString(R.string.workout_type_recovery_run)
    WorkoutType.RACE -> context.getString(R.string.workout_type_competition)
}

/** Макс. разумная скорость между точками (м/с) для фильтра выбросов GPS — зависит от типа тренировки */
fun WorkoutType.maxReasonableGpsSpeedMps(): Float = when (this) {
    WorkoutType.RACE,
    WorkoutType.INTERVAL_TRAINING -> 22f // ~80 км/ч (спуск / интервалы + GPS jitter)
    WorkoutType.TEMPO_RUN -> 18f
    WorkoutType.LONG_RUN,
    WorkoutType.EASY_RUN,
    WorkoutType.RECOVERY_RUN -> 16f // ~58 км/ч
}
