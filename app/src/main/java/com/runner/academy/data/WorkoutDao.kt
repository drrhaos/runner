package com.runner.academy.data

import androidx.paging.PagingSource
import androidx.room.*
import kotlinx.coroutines.flow.Flow

/** An abstract class rather than an interface so the whole-row [updateWorkout] can stay protected. */
@Dao
abstract class WorkoutDao {
    @Query("SELECT * FROM workouts ORDER BY date DESC")
    abstract fun getAllWorkouts(): Flow<List<Workout>>

    /** List pages never load the track: [WorkoutListItem] columns only. */
    @Query("SELECT $LIST_ITEM_COLUMNS FROM workouts ORDER BY date DESC")
    abstract fun pagingSourceAll(): PagingSource<Int, WorkoutListItem>

    @Query("SELECT $LIST_ITEM_COLUMNS FROM workouts WHERE isFavorite = 1 ORDER BY date DESC")
    abstract fun pagingSourceFavorites(): PagingSource<Int, WorkoutListItem>

    /**
     * Route picker: workouts with a track, favorites first; excludes the one being edited.
     * The filter reads the track in SQLite only; the chosen one is loaded by [getTrackData].
     */
    @Query("SELECT $LIST_ITEM_COLUMNS FROM workouts WHERE $ROUTE_FILTER ORDER BY isFavorite DESC, date DESC")
    abstract fun pagingSourceRoutes(excludeId: Long): PagingSource<Int, WorkoutListItem>

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

    /** The track of one workout (the route picker hands it to the form); null without one. */
    @Query("SELECT trackData FROM workouts WHERE id = :id")
    abstract suspend fun getTrackData(id: Long): String?

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

    /** Rows whose derived columns are older than [version], newest first (they head the list). */
    @Query("SELECT id FROM workouts WHERE metricsVersion < :version ORDER BY date DESC LIMIT :limit")
    abstract suspend fun idsWithMetricsBelow(version: Int, limit: Int): List<Long>

    @Query("SELECT COUNT(*) FROM workouts WHERE metricsVersion < :version")
    abstract suspend fun countWithMetricsBelow(version: Int): Int

    /** Of [ids], those still below [version] (not computed yet). */
    @Query("SELECT id FROM workouts WHERE id IN (:ids) AND metricsVersion < :version")
    abstract suspend fun idsAmongWithMetricsBelow(ids: List<Long>, version: Int): List<Long>

    /** True while some workout with a track still waits for its best efforts below [version]. */
    @Query(
        "SELECT EXISTS(SELECT 1 FROM workouts WHERE metricsVersion < :version AND trackData IS NOT NULL LIMIT 1)"
    )
    abstract fun observeTrackedWithMetricsBelow(version: Int): Flow<Boolean>

    /** Only what the metrics are derived from: one track in memory at a time. */
    @Query("SELECT id, trackData, type, duration FROM workouts WHERE id = :id")
    abstract suspend fun getMetricsSource(id: Long): MetricsSourceRow?

    /**
     * Writes derived columns unless the row was saved meanwhile with [version] or newer (an
     * edit computes its own). Returns the number of rows updated: 0 = leave its efforts alone.
     */
    @Query(
        """
        UPDATE workouts SET elevationGain = :elevationGain, elevationLoss = :elevationLoss,
            elevationSource = :elevationSource, avgCadence = :avgCadence,
            routePreview = :routePreview, metricsVersion = :version
        WHERE id = :id AND metricsVersion < :version
        """
    )
    abstract suspend fun updateMetricsIfOlder(
        id: Long,
        version: Int,
        elevationGain: Float?,
        elevationLoss: Float?,
        elevationSource: ElevationSource?,
        avgCadence: Float?,
        routePreview: String?
    ): Int

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
        /** The [WorkoutListItem] columns; `IS NOT NULL` reads only the record header of the track. */
        private const val LIST_ITEM_COLUMNS =
            "id, date, distance, duration, movingDuration, avgPace, calories, notes, type, isFavorite, " +
                "(trackData IS NOT NULL) AS hasTrack, routePreview, metricsVersion"

        /** A workout usable as a route: it has a track and is not the one being edited. */
        private const val ROUTE_FILTER =
            "trackData IS NOT NULL AND TRIM(trackData) != '' AND id != :excludeId"
    }
}
