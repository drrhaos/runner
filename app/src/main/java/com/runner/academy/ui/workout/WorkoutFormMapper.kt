package com.runner.academy.ui.workout

import com.runner.academy.data.Workout
import com.runner.academy.data.WorkoutType
import com.runner.academy.util.PaceMath
import java.util.Date

/**
 * Builds the workout the add/edit form saves. An edit is a copy of the original, so every
 * column the form does not show (favorite, records flag, heart rate, derived metrics) survives.
 */
object WorkoutFormMapper {

    /** What the user entered, already validated by the form. */
    data class FormInput(
        val date: Date,
        val type: WorkoutType,
        val distanceKm: Float,
        /** Whole seconds, as the form shows them. */
        val durationMs: Long,
        val calories: Int?,
        val notes: String?,
        val trackDataJson: String?
    )

    fun toWorkout(form: FormInput, original: Workout?): Workout {
        if (original == null) {
            return Workout(
                date = form.date,
                distance = form.distanceKm,
                duration = form.durationMs,
                // A manual workout has no auto-pause
                movingDuration = form.durationMs,
                avgPace = PaceMath.avgPace(form.distanceKm, form.durationMs),
                calories = form.calories,
                notes = form.notes,
                type = form.type,
                trackData = form.trackDataJson
            )
        }
        // The form drops milliseconds: the same whole seconds keep the exact recorded time
        val timeChanged = form.durationMs != original.duration / 1000 * 1000
        val duration = if (timeChanged) form.durationMs else original.duration
        // Owner's rule: a new time is all moving; otherwise auto-pauses stay subtracted
        val movingDuration = if (timeChanged) form.durationMs else original.movingDuration
        return original.copy(
            date = form.date,
            distance = form.distanceKm,
            duration = duration,
            movingDuration = movingDuration,
            avgPace = PaceMath.avgPace(form.distanceKm, movingDuration),
            calories = form.calories,
            notes = form.notes,
            type = form.type,
            trackData = form.trackDataJson
        )
    }
}
