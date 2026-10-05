package com.runner.academy.data

import androidx.paging.PagingSource
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface WorkoutDao {
    @Query("SELECT * FROM workouts ORDER BY date DESC")
    fun getAllWorkouts(): Flow<List<Workout>>

    @Query("SELECT * FROM workouts ORDER BY date DESC")
    fun pagingSourceAll(): PagingSource<Int, Workout>

    @Query("SELECT * FROM workouts WHERE isFavorite = 1 ORDER BY date DESC")
    fun pagingSourceFavorites(): PagingSource<Int, Workout>

    /** Route picker: workouts with a track, favorites first; excludes the one being edited. */
    @Query("SELECT * FROM workouts WHERE $ROUTE_FILTER ORDER BY isFavorite DESC, date DESC")
    fun pagingSourceRoutes(excludeId: Long): PagingSource<Int, Workout>

    @Query("SELECT COUNT(*) FROM workouts WHERE $ROUTE_FILTER")
    suspend fun countRoutes(excludeId: Long): Int

    /** Statistics need only these columns; tracks stay on disk. */
    @Query(
        "SELECT date, distance, duration, movingDuration, avgPace, calories, type, " +
            "elevationGain, elevationSource, avgCadence FROM workouts"
    )
    suspend fun getStatsRows(): List<WorkoutStatsRow>

    @Query("SELECT * FROM workouts WHERE id = :id")
    fun getWorkoutById(id: Long): Flow<Workout?>

    @Insert
    suspend fun insertWorkout(workout: Workout): Long

    @Insert
    suspend fun insertWorkouts(workouts: List<Workout>): List<Long>

    /** Whole-row update: only through [WorkoutRepository.saveEdited]. */
    @Update
    suspend fun updateWorkout(workout: Workout)

    @Query("UPDATE workouts SET isFavorite = :isFavorite WHERE id = :id")
    suspend fun setFavorite(id: Long, isFavorite: Boolean)

    /** Records are filtered by this flag when read, so nothing has to be recomputed. */
    @Query("UPDATE workouts SET excludeFromRecords = :exclude WHERE id = :id")
    suspend fun setExcludeFromRecords(id: Long, exclude: Boolean)

    @Delete
    suspend fun deleteWorkout(workout: Workout)

    @Query("SELECT SUM(distance) FROM workouts")
    suspend fun getTotalDistance(): Float?

    @Query("SELECT COUNT(*) FROM workouts")
    suspend fun getTotalWorkouts(): Int

    @Query("SELECT COUNT(*) FROM workouts WHERE isFavorite = 1")
    suspend fun getFavoriteWorkoutsCount(): Int

    @Query("SELECT AVG(duration) FROM workouts")
    suspend fun getAverageDuration(): Long?

    @Query("SELECT SUM(duration) FROM workouts")
    suspend fun getTotalDuration(): Long?

    /** The pace of all workouts is over moving time. */
    @Query("SELECT SUM(movingDuration) FROM workouts")
    suspend fun getTotalMovingDuration(): Long?

    companion object {
        /** A workout usable as a route: it has a track and is not the one being edited. */
        private const val ROUTE_FILTER =
            "trackData IS NOT NULL AND TRIM(trackData) != '' AND id != :excludeId"
    }
}
