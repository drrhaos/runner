package com.runner.academy.data

import androidx.paging.PagingSource
import androidx.room.*
import kotlinx.coroutines.flow.Flow

/** An abstract class rather than an interface so the whole-row [updateWorkout] can stay protected. */
@Dao
abstract class WorkoutDao {
    @Query("SELECT * FROM workouts ORDER BY date DESC")
    abstract fun getAllWorkouts(): Flow<List<Workout>>

    @Query("SELECT * FROM workouts ORDER BY date DESC")
    abstract fun pagingSourceAll(): PagingSource<Int, Workout>

    @Query("SELECT * FROM workouts WHERE isFavorite = 1 ORDER BY date DESC")
    abstract fun pagingSourceFavorites(): PagingSource<Int, Workout>

    /** Route picker: workouts with a track, favorites first; excludes the one being edited. */
    @Query("SELECT * FROM workouts WHERE $ROUTE_FILTER ORDER BY isFavorite DESC, date DESC")
    abstract fun pagingSourceRoutes(excludeId: Long): PagingSource<Int, Workout>

    @Query("SELECT COUNT(*) FROM workouts WHERE $ROUTE_FILTER")
    abstract suspend fun countRoutes(excludeId: Long): Int

    /** Statistics need only these columns; tracks stay on disk. */
    @Query(
        "SELECT date, distance, duration, movingDuration, avgPace, calories, type, " +
            "elevationGain, elevationSource, avgCadence FROM workouts"
    )
    abstract suspend fun getStatsRows(): List<WorkoutStatsRow>

    @Query("SELECT * FROM workouts WHERE id = :id")
    abstract fun getWorkoutById(id: Long): Flow<Workout?>

    @Insert
    abstract suspend fun insertWorkout(workout: Workout): Long

    @Insert
    abstract suspend fun insertWorkouts(workouts: List<Workout>): List<Long>

    /**
     * The only whole-row rewrite. [workout] must be a copy of the stored row (see
     * [com.runner.academy.ui.workout.WorkoutFormMapper]) so columns the editor does not know
     * about survive. Single flags have their own point updates.
     */
    suspend fun saveEdited(workout: Workout) {
        require(workout.id != 0L) { "saveEdited needs a stored workout" }
        updateWorkout(workout)
    }

    @Update
    protected abstract suspend fun updateWorkout(workout: Workout)

    @Query("UPDATE workouts SET isFavorite = :isFavorite WHERE id = :id")
    abstract suspend fun setFavorite(id: Long, isFavorite: Boolean)

    /** Records are filtered by this flag when read, so nothing has to be recomputed. */
    @Query("UPDATE workouts SET excludeFromRecords = :exclude WHERE id = :id")
    abstract suspend fun setExcludeFromRecords(id: Long, exclude: Boolean)

    @Delete
    abstract suspend fun deleteWorkout(workout: Workout)

    @Query("SELECT SUM(distance) FROM workouts")
    abstract suspend fun getTotalDistance(): Float?

    @Query("SELECT COUNT(*) FROM workouts")
    abstract suspend fun getTotalWorkouts(): Int

    @Query("SELECT COUNT(*) FROM workouts WHERE isFavorite = 1")
    abstract suspend fun getFavoriteWorkoutsCount(): Int

    /** "Average time" is over moving time, like the statistics screen. */
    @Query("SELECT AVG(movingDuration) FROM workouts")
    abstract suspend fun getAverageMovingDuration(): Long?

    @Query("SELECT SUM(duration) FROM workouts")
    abstract suspend fun getTotalDuration(): Long?

    /** The pace of all workouts is over moving time. */
    @Query("SELECT SUM(movingDuration) FROM workouts")
    abstract suspend fun getTotalMovingDuration(): Long?

    companion object {
        /** A workout usable as a route: it has a track and is not the one being edited. */
        private const val ROUTE_FILTER =
            "trackData IS NOT NULL AND TRIM(trackData) != '' AND id != :excludeId"
    }
}
