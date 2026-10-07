package com.runner.academy.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface BestEffortDao {

    /** Replaces the efforts of [workoutId] with [efforts] atomically. */
    @Transaction
    suspend fun replaceForWorkout(workoutId: Long, efforts: List<BestEffort>) {
        require(efforts.all { it.workoutId == workoutId }) { "Efforts of another workout" }
        deleteForWorkout(workoutId)
        if (efforts.isNotEmpty()) insertAll(efforts)
    }

    @Insert
    suspend fun insertAll(efforts: List<BestEffort>)

    @Query("DELETE FROM best_efforts WHERE workoutId = :workoutId")
    suspend fun deleteForWorkout(workoutId: Long)

    @Query("SELECT * FROM best_efforts WHERE workoutId = :workoutId ORDER BY distanceM")
    suspend fun getForWorkout(workoutId: Long): List<BestEffort>

    /** Every effort that may count as a record: workouts excluded by the user are filtered here. */
    @Query(ELIGIBLE_EFFORTS)
    fun observeEligibleEfforts(): Flow<List<EffortRow>>

    /** [observeEligibleEfforts] once: a snapshot to compare records before and after a change. */
    @Query(ELIGIBLE_EFFORTS)
    suspend fun getEligibleEfforts(): List<EffortRow>
}

/** The records now: [BestEffortDao.getEligibleEfforts] as a [RecordBook]. */
suspend fun BestEffortDao.recordBook(): RecordBook = RecordBook.from(getEligibleEfforts())

private const val ELIGIBLE_EFFORTS = """
    SELECT b.*, w.date, w.type
    FROM best_efforts b JOIN workouts w ON w.id = b.workoutId
    WHERE w.excludeFromRecords = 0
"""
