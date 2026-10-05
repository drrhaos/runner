package com.runner.academy.data

import androidx.paging.PagingSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

    /** Imports GPX workouts as new rows (new ids), with their metrics computed before saving. */
    suspend fun importGpx(workouts: List<Workout>): List<Long> = importAsNew(workouts, WorkoutStore.Mode.INLINE)

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

    suspend fun setExcludeFromRecords(id: Long, exclude: Boolean) = withContext(Dispatchers.IO) {
        workoutDao.setExcludeFromRecords(id, exclude)
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
