package com.runner.academy.data

import androidx.room.withTransaction
import com.runner.academy.util.DerivationInput
import com.runner.academy.util.Derived
import com.runner.academy.util.WorkoutDerivation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The only writer of whole workout rows: each row is saved together with its derived columns
 * and best efforts, in one transaction, so they never disagree with the track.
 */
class WorkoutStore(
    private val database: WorkoutDatabase,
    private val derive: (DerivationInput) -> Derived = WorkoutDerivation::derive,
    /**
     * Called with the new ids after a [Mode.DEFERRED] save: starts the background metrics pass,
     * which reports the records of these rows ("Records updated: N").
     */
    private val onDeferredSaved: (List<Long>) -> Unit = {}
) {
    enum class Mode {
        /** Live recording, the form, GPX import: metrics are computed before the row is written. */
        INLINE,

        /** Backup import (many rows at once): rows are written uncomputed and derived in background. */
        DEFERRED
    }

    private val workoutDao get() = database.workoutDao()
    private val bestEffortDao get() = database.bestEffortDao()

    suspend fun insert(workout: Workout): Long = insertAll(listOf(workout), Mode.INLINE).single()

    /** Inserts with new auto-generated ids (the given ids must be 0); returns them in order. */
    suspend fun insertAll(workouts: List<Workout>, mode: Mode): List<Long> {
        if (workouts.isEmpty()) return emptyList()
        return when (mode) {
            Mode.INLINE -> {
                val derived = withContext(Dispatchers.Default) { workouts.map { derive(it.derivationInput()) } }
                database.withTransaction {
                    workouts.zip(derived) { workout, metrics ->
                        val id = workoutDao.insertWorkout(metrics.applyTo(workout))
                        bestEffortDao.replaceForWorkout(id, metrics.efforts.map { it.toBestEffort(id) })
                        id
                    }
                }
            }
            Mode.DEFERRED -> {
                val ids = workoutDao.insertWorkouts(workouts.map { it.copy(metricsVersion = 0) })
                onDeferredSaved(ids)
                ids
            }
        }
    }

    /** Saves an edited copy of a stored row (see [WorkoutDao.saveEdited]) with fresh metrics. */
    suspend fun saveEdited(workout: Workout) {
        val metrics = withContext(Dispatchers.Default) { derive(workout.derivationInput()) }
        database.withTransaction {
            workoutDao.saveEdited(metrics.applyTo(workout))
            bestEffortDao.replaceForWorkout(workout.id, metrics.efforts.map { it.toBestEffort(workout.id) })
        }
    }

    private fun Workout.derivationInput() = DerivationInput(trackData, type, duration)
}
