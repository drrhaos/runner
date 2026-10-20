package com.runner.academy.ui.workout

import com.runner.academy.data.Workout
import com.runner.academy.data.WorkoutType
import com.runner.academy.util.CalorieProfile
import com.runner.academy.util.Calories
import com.runner.academy.util.PaceMath
import java.util.Date

/**
 * Builds the workout the add/edit form saves. An edit is a copy of the original, so every
 * column the form does not show (favorite, records flag, heart rate, derived metrics) survives.
 * The automatic calories follow the same moving-time rule ([autoCalories]).
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
        val trackDataJson: String?,
        /** The name the user gave (or the file did); blank — none. */
        val title: String? = null
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
                trackData = form.trackDataJson,
                title = WorkoutTitle.clean(form.title)
            )
        }
        val timeChanged = timeChanged(form.durationMs, original)
        val duration = if (timeChanged) form.durationMs else original.duration
        val movingDuration = movingDuration(form.durationMs, original)
        return original.copy(
            date = form.date,
            distance = form.distanceKm,
            duration = duration,
            movingDuration = movingDuration,
            avgPace = PaceMath.avgPace(form.distanceKm, movingDuration),
            calories = form.calories,
            notes = form.notes,
            type = form.type,
            trackData = form.trackDataJson,
            title = WorkoutTitle.clean(form.title)
        )
    }

    /**
     * The calories the form fills in while the field is automatic: the moving time [toWorkout]
     * would save; an edit keeps the original's climb and average heart rate (a new workout has
     * neither until saved).
     */
    fun autoCalories(profile: CalorieProfile, distanceKm: Float, durationMs: Long, original: Workout?): Int =
        Calories.estimate(
            profile,
            distanceM = distanceKm * 1000f,
            movingMs = movingDuration(durationMs, original),
            climbM = original?.elevationGain,
            avgHeartRate = original?.avgHeartRate
        )

    /**
     * The stored calories of [workout] are the automatic ones, so the form keeps recomputing them:
     * none, [autoCalories] of the form as loaded, or distance × weight of older versions.
     */
    fun caloriesAreAuto(workout: Workout, profile: CalorieProfile): Boolean {
        val calories = workout.calories ?: return true
        val shownDurationMs = workout.duration / 1000 * 1000
        return calories == autoCalories(profile, workout.distance, shownDurationMs, workout) ||
            calories == (workout.distance * profile.weightKg).toInt()
    }

    /** The form drops milliseconds: the same whole seconds keep the exact recorded time. */
    private fun timeChanged(durationMs: Long, original: Workout): Boolean =
        durationMs != original.duration / 1000 * 1000

    /** Owner's rule: a new time is all moving; otherwise auto-pauses stay subtracted. */
    private fun movingDuration(durationMs: Long, original: Workout?): Long =
        if (original == null || timeChanged(durationMs, original)) durationMs else original.movingDuration
}
