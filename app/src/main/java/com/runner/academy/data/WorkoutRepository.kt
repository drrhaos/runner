package com.runner.academy.data

import androidx.paging.PagingSource
import com.runner.academy.util.WorkoutDerivation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** New rows of a GPX import and the record distances it changed ("Records updated: N"). */
data class GpxImport(val ids: List<Long>, val changedRecordDistances: Int)

/**
 * Repository layer that abstracts data access from the Room database.
 * Provides a single source of truth for workout data and decouples
 * ViewModels from the underlying data source implementation.
 */
class WorkoutRepository(
    database: WorkoutDatabase,
    private val diagnosticsStore: GpsDiagnosticsStore? = null,
    /** Starts the background metrics pass after rows were saved uncomputed. */
    onDeferredSaved: () -> Unit = {}
) {
    private val workoutDao: WorkoutDao = database.workoutDao()
    private val bestEffortDao: BestEffortDao = database.bestEffortDao()

    /** Every whole-row write goes through it, with the derived metrics. */
    private val store = WorkoutStore(database, onDeferredSaved = onDeferredSaved)

    /**
     * Get all workouts ordered by date descending as a Flow.
     */
    fun getAllWorkouts(): Flow<List<Workout>> = workoutDao.getAllWorkouts()

    fun pagingSourceAll(): PagingSource<Int, WorkoutListItem> = workoutDao.pagingSourceAll()

    fun pagingSourceFavorites(): PagingSource<Int, WorkoutListItem> = workoutDao.pagingSourceFavorites()

    fun pagingSourceRoutes(excludeId: Long): PagingSource<Int, WorkoutListItem> =
        workoutDao.pagingSourceRoutes(excludeId)

    /** The track of one workout, read when a route is picked; null without one. */
    suspend fun getTrackData(id: Long): String? = withContext(Dispatchers.IO) {
        workoutDao.getTrackData(id)
    }

    suspend fun countRoutes(excludeId: Long): Int = withContext(Dispatchers.IO) {
        workoutDao.countRoutes(excludeId)
    }

    suspend fun getStatsRows(): List<WorkoutStatsRow> = withContext(Dispatchers.IO) {
        workoutDao.getStatsRows()
    }

    /**
     * Get a single workout by ID as a Flow.
     */
    fun getWorkoutById(id: Long): Flow<Workout?> = workoutDao.getWorkoutById(id)

    /** Inserts a new workout with its metrics computed and returns the generated row ID. */
    suspend fun insertWorkout(workout: Workout): Long = withContext(Dispatchers.IO) {
        store.insert(workout)
    }

    /**
     * Imports a backup as new rows (new ids). Hundreds of rows at once: they are saved
     * uncomputed and derived by the background pass ([WorkoutStore.Mode.DEFERRED]).
     */
    suspend fun importBackup(workouts: List<Workout>): List<Long> = importAsNew(workouts, WorkoutStore.Mode.DEFERRED)

    /**
     * Imports GPX workouts as new rows (new ids), with their metrics computed before saving.
     * The records change silently: the result tells how many distances got another record.
     */
    suspend fun importGpx(workouts: List<Workout>): GpxImport {
        val before = recordBook()
        val ids = importAsNew(workouts, WorkoutStore.Mode.INLINE)
        return GpxImport(ids, RecordBook.changedDistances(before, recordBook()))
    }

    private suspend fun importAsNew(workouts: List<Workout>, mode: WorkoutStore.Mode): List<Long> =
        withContext(Dispatchers.IO) {
            store.insertAll(workouts.map { it.copy(id = 0) }, mode)
        }

    /** Saves an edited workout as a whole row with fresh metrics; see [WorkoutDao.saveEdited]. */
    suspend fun saveEdited(workout: Workout) = withContext(Dispatchers.IO) {
        store.saveEdited(workout)
    }

    suspend fun setFavorite(id: Long, isFavorite: Boolean) = withContext(Dispatchers.IO) {
        workoutDao.setFavorite(id, isFavorite)
    }

    /** Takes the workout out of the records (or back): the records follow at once, nothing is recomputed. */
    suspend fun setExcludeFromRecords(id: Long, exclude: Boolean) = withContext(Dispatchers.IO) {
        workoutDao.setExcludeFromRecords(id, exclude)
    }

    /**
     * Records and their history, live: any save, deletion, exclusion, date change or background
     * pass re-emits it (Room invalidates on `best_efforts` and `workouts`).
     */
    fun observeRecordBook(): Flow<RecordBook> =
        bestEffortDao.observeEligibleEfforts().map(RecordBook::from).flowOn(Dispatchers.Default)

    /** The records once, e.g. before and after an import. */
    suspend fun recordBook(): RecordBook = withContext(Dispatchers.IO) {
        RecordBook.from(bestEffortDao.getEligibleEfforts())
    }

    /**
     * True while workouts with a track are not yet computed by the current algorithms (after an
     * update or a backup import): the records are incomplete until the background pass ends.
     */
    fun observeRecordsPending(): Flow<Boolean> =
        workoutDao.observeTrackedWithMetricsBelow(WorkoutDerivation.CURRENT_METRICS_VERSION)

    /**
     * The record card of a workout's details ([RecordBook.cardFor]); null when it set no
     * record, and while [observeRecordsPending]: the first run right after an update would
     * otherwise "beat" records not computed yet.
     */
    fun observeRecordCard(workoutId: Long, justSaved: Boolean): Flow<RecordCard?> =
        combine(observeRecordBook(), observeRecordsPending()) { book, pending ->
            if (pending) null else book.cardFor(workoutId, justSaved)
        }

    /**
     * Delete a workout.
     */
    suspend fun deleteWorkout(workout: Workout) = withContext(Dispatchers.IO) {
        workoutDao.deleteWorkout(workout)
        diagnosticsStore?.delete(workout.id)
    }

    /** Keeps the GPS diagnostics recording of the session started at [startTimeMs] with the saved workout. */
    suspend fun attachGpsDiagnostics(startTimeMs: Long, workoutId: Long) = withContext(Dispatchers.IO) {
        diagnosticsStore?.attachToWorkout(startTimeMs, workoutId)
    }

    suspend fun hasGpsDiagnostics(workoutId: Long): Boolean = withContext(Dispatchers.IO) {
        diagnosticsStore?.workoutFile(workoutId) != null
    }

    /** A copy for the share sheet, or null when the workout has no recording. */
    suspend fun copyGpsDiagnosticsForSharing(workoutId: Long): java.io.File? = withContext(Dispatchers.IO) {
        diagnosticsStore?.copyForSharing(workoutId)
    }

    /**
     * Get the total distance across all workouts.
     */
    suspend fun getTotalDistance(): Float? = withContext(Dispatchers.IO) {
        workoutDao.getTotalDistance()
    }

    /**
     * Get the total number of workouts.
     */
    suspend fun getTotalWorkouts(): Int = withContext(Dispatchers.IO) {
        workoutDao.getTotalWorkouts()
    }

    suspend fun getFavoriteWorkoutsCount(): Int = withContext(Dispatchers.IO) {
        workoutDao.getFavoriteWorkoutsCount()
    }

    /** The average moving time across all workouts. */
    suspend fun getAverageMovingDuration(): Long? = withContext(Dispatchers.IO) {
        workoutDao.getAverageMovingDuration()
    }

    suspend fun getTotalDuration(): Long? = withContext(Dispatchers.IO) {
        workoutDao.getTotalDuration()
    }

    suspend fun getTotalMovingDuration(): Long? = withContext(Dispatchers.IO) {
        workoutDao.getTotalMovingDuration()
    }
}
