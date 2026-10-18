package com.runner.academy.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * An imported workout that happened at the same time as another and waits for the user's
 * decision ([ConflictResolution]) on the import conflicts screen. Kept apart from `workouts`:
 * until decided it shows in no list, statistic or record. What it overlaps is looked up when
 * shown, so a workout deleted meanwhile is no conflict any more.
 */
@Entity(tableName = "pending_imports", indices = [Index(value = ["startMs"])])
data class PendingImport(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val startMs: Long,
    val durationMs: Long,
    val distanceKm: Float,
    val hasTrack: Boolean,
    /** The file it came from; null for a backup row. */
    val source: String?,
    /** The workout as a backup holds it ([com.runner.academy.util.WorkoutBackupFormat.toWorkoutJson]). */
    val workoutJson: String
)

/** A [PendingImport] without its (large) workout JSON: what the screen and the checks need. */
data class PendingImportSummary(
    val id: Long,
    val startMs: Long,
    val durationMs: Long,
    val distanceKm: Float,
    val hasTrack: Boolean,
    val source: String?
)

private const val SUMMARY_COLUMNS = "id, startMs, durationMs, distanceKm, hasTrack, source"

@Dao
interface PendingImportDao {

    @Insert
    suspend fun insert(pending: PendingImport): Long

    @Query("SELECT * FROM pending_imports WHERE id = :id")
    suspend fun get(id: Long): PendingImport?

    @Query("DELETE FROM pending_imports WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT $SUMMARY_COLUMNS FROM pending_imports ORDER BY startMs")
    fun observeAll(): Flow<List<PendingImportSummary>>

    @Query("SELECT $SUMMARY_COLUMNS FROM pending_imports ORDER BY startMs")
    suspend fun all(): List<PendingImportSummary>

    @Query("SELECT COUNT(*) FROM pending_imports")
    fun observeCount(): Flow<Int>

    /** The waiting workouts whose time touches [fromMs]..[toMs], as [WorkoutDao.listItemsOverlapping]. */
    @Query(
        "SELECT $SUMMARY_COLUMNS FROM pending_imports " +
            "WHERE startMs <= :toMs AND startMs + MAX(durationMs, 1000) - 1 >= :fromMs ORDER BY startMs"
    )
    suspend fun overlapping(fromMs: Long, toMs: Long): List<PendingImportSummary>
}
