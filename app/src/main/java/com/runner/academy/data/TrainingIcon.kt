package com.runner.academy.data

import android.content.Context
import androidx.annotation.DrawableRes
import com.runner.academy.R

/**
 * Visual mark for intensity of a base workout or a training plan.
 * Defaults map cleanly from [WorkoutType].
 */
enum class TrainingIcon {
    RECOVERY,
    EASY,
    LONG,
    TEMPO,
    INTERVAL,
    RACE,
    PLAN
}

fun WorkoutType.defaultTrainingIcon(): TrainingIcon = when (this) {
    WorkoutType.RECOVERY_RUN -> TrainingIcon.RECOVERY
    WorkoutType.EASY_RUN -> TrainingIcon.EASY
    WorkoutType.LONG_RUN -> TrainingIcon.LONG
    WorkoutType.TEMPO_RUN -> TrainingIcon.TEMPO
    WorkoutType.INTERVAL_TRAINING -> TrainingIcon.INTERVAL
    WorkoutType.RACE -> TrainingIcon.RACE
}

fun parseTrainingIcon(name: String?, fallback: TrainingIcon = TrainingIcon.EASY): TrainingIcon {
    if (name.isNullOrBlank()) return fallback
    return TrainingIcon.entries.find { it.name == name } ?: fallback
}

@DrawableRes
fun TrainingIcon.drawableRes(): Int = when (this) {
    TrainingIcon.RECOVERY -> R.drawable.ic_intensity_recovery
    TrainingIcon.EASY -> R.drawable.ic_intensity_easy
    TrainingIcon.LONG -> R.drawable.ic_intensity_long
    TrainingIcon.TEMPO -> R.drawable.ic_intensity_tempo
    TrainingIcon.INTERVAL -> R.drawable.ic_intensity_interval
    TrainingIcon.RACE -> R.drawable.ic_intensity_race
    TrainingIcon.PLAN -> R.drawable.ic_intensity_plan
}

fun TrainingIcon.displayName(context: Context): String = when (this) {
    TrainingIcon.RECOVERY -> context.getString(R.string.intensity_icon_recovery)
    TrainingIcon.EASY -> context.getString(R.string.intensity_icon_easy)
    TrainingIcon.LONG -> context.getString(R.string.intensity_icon_long)
    TrainingIcon.TEMPO -> context.getString(R.string.intensity_icon_tempo)
    TrainingIcon.INTERVAL -> context.getString(R.string.intensity_icon_interval)
    TrainingIcon.RACE -> context.getString(R.string.intensity_icon_race)
    TrainingIcon.PLAN -> context.getString(R.string.intensity_icon_plan)
}
