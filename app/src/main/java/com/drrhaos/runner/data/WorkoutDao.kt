package com.drrhaos.runner.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface WorkoutDao {
    @Query("SELECT * FROM workouts ORDER BY date DESC")
    fun getAllWorkouts(): Flow<List<Workout>>

    @Query("SELECT * FROM workouts WHERE id = :id")
    fun getWorkoutById(id: Long): Flow<Workout?>

    @Insert
    suspend fun insertWorkout(workout: Workout): Long

    @Update
    suspend fun updateWorkout(workout: Workout)

    @Delete
    suspend fun deleteWorkout(workout: Workout)

    @Query("SELECT SUM(distance) FROM workouts")
    suspend fun getTotalDistance(): Float?

    @Query("SELECT COUNT(*) FROM workouts")
    suspend fun getTotalWorkouts(): Int

    @Query("SELECT AVG(duration) FROM workouts")
    suspend fun getAverageDuration(): Long?
}
