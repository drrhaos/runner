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
    private val workoutDao: WorkoutDao,
    private val diagnosticsStore: GpsDiagnosticsStore? = null
) {

    /**
     * Get all workouts ordered by date descending as a Flow.
     */
    fun getAllWorkouts(): Flow<List<Workout>> = workoutDao.getAllWorkouts()

    fun pagingSourceAll(): PagingSource<Int, Workout> = workoutDao.pagingSourceAll()

    fun pagingSourceFavorites(): PagingSource<Int, Workout> = workoutDao.pagingSourceFavorites()

    fun pagingSourceRoutes(excludeId: Long): PagingSource<Int, Workout> =
        workoutDao.pagingSourceRoutes(excludeId)

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

    /**
     * Insert a new workout and return the generated row ID.
     */
    suspend fun insertWorkout(workout: Workout): Long = withContext(Dispatchers.IO) {
        workoutDao.insertWorkout(workout)
    }

    /**
     * Insert multiple workouts (IDs should be 0 for auto-generation).
     */
    suspend fun insertWorkouts(workouts: List<Workout>): List<Long> = withContext(Dispatchers.IO) {
        workoutDao.insertWorkouts(workouts)
    }

    /**
     * Saves an edited workout as a whole row. The only way to rewrite a row: [workout] must be a
     * copy of the stored one (see [com.runner.academy.ui.workout.WorkoutFormMapper]) so columns
     * the editor does not know about survive. Single flags have their own point updates.
     */
    suspend fun saveEdited(workout: Workout) = withContext(Dispatchers.IO) {
        require(workout.id != 0L) { "saveEdited needs a stored workout" }
        workoutDao.updateWorkout(workout)
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

    /**
     * Get the average duration across all workouts.
     */
    suspend fun getAverageDuration(): Long? = withContext(Dispatchers.IO) {
        workoutDao.getAverageDuration()
    }

    suspend fun getTotalDuration(): Long? = withContext(Dispatchers.IO) {
        workoutDao.getTotalDuration()
    }

    suspend fun getTotalMovingDuration(): Long? = withContext(Dispatchers.IO) {
        workoutDao.getTotalMovingDuration()
    }
}
