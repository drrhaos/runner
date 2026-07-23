package com.drrhaos.runner.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Repository layer that abstracts data access from the Room database.
 * Provides a single source of truth for workout data and decouples
 * ViewModels from the underlying data source implementation.
 */
class WorkoutRepository(private val workoutDao: WorkoutDao) {

    /**
     * Get all workouts ordered by date descending as a Flow.
     */
    fun getAllWorkouts(): Flow<List<Workout>> = workoutDao.getAllWorkouts()

    /**
     * Get a single workout by ID as a Flow.
     */
    fun getWorkoutById(id: Long): Flow<Workout?> = workoutDao.getWorkoutById(id)

    /**
     * Get workouts within a date range ordered by date descending as a Flow.
     */
    fun getWorkoutsByDateRange(startDate: Long, endDate: Long): Flow<List<Workout>> =
        workoutDao.getWorkoutsByDateRange(startDate, endDate)

    /**
     * Insert a new workout and return the generated row ID.
     */
    suspend fun insertWorkout(workout: Workout): Long = withContext(Dispatchers.IO) {
        workoutDao.insertWorkout(workout)
    }

    /**
     * Update an existing workout.
     */
    suspend fun updateWorkout(workout: Workout) = withContext(Dispatchers.IO) {
        workoutDao.updateWorkout(workout)
    }

    /**
     * Delete a workout.
     */
    suspend fun deleteWorkout(workout: Workout) = withContext(Dispatchers.IO) {
        workoutDao.deleteWorkout(workout)
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

    /**
     * Get the average duration across all workouts.
     */
    suspend fun getAverageDuration(): Long? = withContext(Dispatchers.IO) {
        workoutDao.getAverageDuration()
    }
}
