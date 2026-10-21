package com.runner.academy.data

import androidx.room.withTransaction
import com.runner.academy.util.DerivationInput
import com.runner.academy.util.Derived
import com.runner.academy.util.WorkoutBackupFormat
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
        /** Live recording, the form, GPX / TCX import: metrics are computed before the row is written. */
        INLINE,

        /** Backup import (many rows at once): rows are written uncomputed and derived in background. */
        DEFERRED
    }

    private val workoutDao get() = database.workoutDao()
    private val bestEffortDao get() = database.bestEffortDao()
    private val pendingDao get() = database.pendingImportDao()

    suspend fun insert(workout: Workout): Long = insertAll(listOf(workout), Mode.INLINE).single()

    /** Inserts with new auto-generated ids (the given ids must be 0); returns them in order. */
    suspend fun insertAll(workouts: List<Workout>, mode: Mode): List<Long> {
        if (workouts.isEmpty()) return emptyList()
        val derived = derivedFor(workouts, mode)
        val ids = database.withTransaction {
            workouts.mapIndexed { index, workout -> insertRow(workout, derived?.get(index)) }
        }
        if (mode == Mode.DEFERRED) onDeferredSaved(ids)
        return ids
    }

    /**
     * Writes one batch of an import in one transaction, each candidate in turn against what is
     * saved and waiting by then (the batch's earlier rows included): a copy of one of them
     * ([ImportConflicts.isSame]) is dropped, one overlapping another waits for the user
     * ([PendingImport]), the rest are saved as new rows (new ids).
     */
    suspend fun importBatch(candidates: List<ImportCandidate>, mode: Mode): ImportBatch {
        if (candidates.isEmpty()) return ImportBatch(emptyList(), pending = 0, duplicates = 0)
        val workouts = candidates.map { it.workout.copy(id = 0) }
        // For every candidate: the few that wait are not worth a second pass
        val derived = derivedFor(workouts, mode)
        var pending = 0
        var duplicates = 0
        val ids = database.withTransaction {
            val inserted = mutableListOf<Long>()
            workouts.forEachIndexed { index, workout ->
                val side = ImportConflicts.side(workout)
                val overlapping = overlapping(side.startMs, side.durationMs)
                when {
                    overlapping.any { ImportConflicts.isSame(it, side) } -> duplicates++
                    overlapping.isNotEmpty() -> {
                        pendingDao.insert(pendingOf(workout, candidates[index].source))
                        pending++
                    }
                    else -> inserted += insertRow(workout, derived?.get(index))
                }
            }
            inserted
        }
        // After the commit: the background pass reads these rows
        if (mode == Mode.DEFERRED && ids.isNotEmpty()) onDeferredSaved(ids)
        return ImportBatch(ids, pending, duplicates)
    }

    /**
     * The saved and the waiting workouts whose time touches [startMs] for [durationMs]
     * ([ImportConflicts.spanOf]), saved ones first.
     */
    suspend fun overlapping(startMs: Long, durationMs: Long): List<ConflictSide> {
        val span = ImportConflicts.spanOf(startMs, durationMs)
        return workoutDao.listItemsOverlapping(span.first, span.last).map(ImportConflicts::side) +
            pendingDao.overlapping(span.first, span.last).map(ImportConflicts::side)
    }

    /** A decided waiting workout: its new id (none when dropped) and the ids it replaced. */
    data class Resolved(val insertedId: Long?, val replacedIds: List<Long>)

    /**
     * Applies the user's [resolution] to the waiting workout [pendingId], in one transaction:
     * dropped, saved beside the others, or saved in place of every saved workout it overlaps
     * now except [keepIds] (saved by the same decision for all: a waiting workout replaces what
     * was there, not another one just imported). Null when it waits no more.
     */
    suspend fun resolvePending(
        pendingId: Long,
        resolution: ConflictResolution,
        keepIds: Set<Long> = emptySet()
    ): Resolved? {
        val pending = pendingDao.get(pendingId) ?: return null
        if (resolution == ConflictResolution.KEEP_EXISTING) {
            pendingDao.delete(pendingId)
            return Resolved(insertedId = null, replacedIds = emptyList())
        }
        val workout = WorkoutBackupFormat.parseWorkoutJson(pending.workoutJson)
        // One row: derived at once, whatever the import was
        val metrics = withContext(Dispatchers.Default) { derive(workout.derivationInput()) }
        return database.withTransaction {
            if (pendingDao.get(pendingId) == null) return@withTransaction null
            val replaced = if (resolution == ConflictResolution.REPLACE) {
                val span = ImportConflicts.spanOf(pending.startMs, pending.durationMs)
                workoutDao.listItemsOverlapping(span.first, span.last).map { it.id } - keepIds
            } else {
                emptyList()
            }
            if (replaced.isNotEmpty()) workoutDao.deleteWorkouts(replaced)
            val id = insertRow(workout, metrics)
            pendingDao.delete(pendingId)
            Resolved(id, replaced)
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

    /** The metrics of [workouts] for [Mode.INLINE]; null for [Mode.DEFERRED] (derived later). */
    private suspend fun derivedFor(workouts: List<Workout>, mode: Mode): List<Derived>? = when (mode) {
        Mode.INLINE -> withContext(Dispatchers.Default) { workouts.map { derive(it.derivationInput()) } }
        Mode.DEFERRED -> null
    }

    /** One new row: with its [metrics] and best efforts, or uncomputed for the background pass. */
    private suspend fun insertRow(workout: Workout, metrics: Derived?): Long {
        if (metrics == null) return workoutDao.insertWorkout(workout.copy(metricsVersion = 0))
        val id = workoutDao.insertWorkout(metrics.applyTo(workout))
        bestEffortDao.replaceForWorkout(id, metrics.efforts.map { it.toBestEffort(id) })
        return id
    }

    private fun pendingOf(workout: Workout, source: String?) = PendingImport(
        startMs = workout.date.time,
        durationMs = workout.duration,
        distanceKm = workout.distance,
        hasTrack = workout.trackData != null,
        source = source,
        workoutJson = WorkoutBackupFormat.toWorkoutJson(workout)
    )

    private fun Workout.derivationInput() = DerivationInput(trackData, type, duration)
}
