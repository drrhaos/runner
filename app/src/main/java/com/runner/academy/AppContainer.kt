package com.runner.academy

import android.content.Context
import com.runner.academy.data.MetricsBackfill
import com.runner.academy.data.TrainingPlanRepository
import com.runner.academy.data.WorkoutDatabase
import com.runner.academy.data.WorkoutRepository
import com.runner.academy.data.GpsDiagnosticsStore
import com.runner.academy.service.ActiveWorkoutStore
import com.runner.academy.util.UserPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Manual DI graph for the app. Constructed once on [RunnerApplication].
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    /** Work that outlives screens (the metrics pass); lives as long as the process. */
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database: WorkoutDatabase by lazy { WorkoutDatabase.getDatabase(appContext) }

    val workoutRepository: WorkoutRepository by lazy {
        WorkoutRepository(database, gpsDiagnosticsStore, onDeferredSaved = { metricsBackfill.start() })
    }

    /** Derived metrics of rows saved without them; started on app start and after an import. */
    val metricsBackfill: MetricsBackfill by lazy {
        val activeWorkout = ActiveWorkoutStore(appContext)
        MetricsBackfill(database, applicationScope, isWorkoutActive = { activeWorkout.exists() })
    }

    val trainingPlanRepository: TrainingPlanRepository by lazy {
        TrainingPlanRepository(
            templateDao = database.workoutTemplateDao(),
            planDao = database.trainingPlanDao(),
            scheduleDao = database.planScheduleDao()
        )
    }

    val userPreferences: UserPreferences by lazy { UserPreferences(appContext) }

    val gpsDiagnosticsStore: GpsDiagnosticsStore by lazy {
        GpsDiagnosticsStore(
            dir = java.io.File(appContext.filesDir, GpsDiagnosticsStore.DIRECTORY),
            shareDir = java.io.File(appContext.cacheDir, GpsDiagnosticsStore.SHARE_DIRECTORY)
        )
    }
}

fun Context.appContainer(): AppContainer =
    (applicationContext as RunnerApplication).container
