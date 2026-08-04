package com.runner.academy

import android.content.Context
import com.runner.academy.data.TrainingPlanRepository
import com.runner.academy.data.WorkoutDatabase
import com.runner.academy.data.WorkoutRepository
import com.runner.academy.util.UserPreferences

/**
 * Manual DI graph for the app. Constructed once on [RunnerApplication].
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val database: WorkoutDatabase by lazy { WorkoutDatabase.getDatabase(appContext) }

    val workoutRepository: WorkoutRepository by lazy {
        WorkoutRepository(database.workoutDao())
    }

    val trainingPlanRepository: TrainingPlanRepository by lazy {
        TrainingPlanRepository(
            templateDao = database.workoutTemplateDao(),
            planDao = database.trainingPlanDao(),
            scheduleDao = database.planScheduleDao()
        )
    }

    val userPreferences: UserPreferences by lazy { UserPreferences(appContext) }
}

fun Context.appContainer(): AppContainer =
    (applicationContext as RunnerApplication).container
