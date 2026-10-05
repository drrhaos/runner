package com.runner.academy.data

import android.content.Context
import androidx.paging.PagingSource
import androidx.test.core.app.ApplicationProvider
import com.runner.academy.ui.workout.WorkoutViewModel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Date

/**
 * Hundreds of workouts must not be loaded at once with their tracks: statistics read only
 * scalar columns, and lists page with a bounded window that drops pages scrolled past.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WorkoutBoundedLoadingTest {

    private lateinit var database: WorkoutDatabase
    private lateinit var dao: WorkoutDao

    @Before
    fun setUp() {
        database = WorkoutDatabase.getInMemoryDatabase(ApplicationProvider.getApplicationContext<Context>())
        dao = database.workoutDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun workout(
        dateMs: Long,
        distance: Float = 5f,
        type: WorkoutType = WorkoutType.EASY_RUN,
        track: String? = "{\"points\":[]}",
        favorite: Boolean = false
    ) = Workout(
        date = Date(dateMs),
        distance = distance,
        duration = 1_800_000L,
        movingDuration = 1_800_000L,
        avgPace = 6f,
        calories = 300,
        notes = null,
        type = type,
        trackData = track,
        isFavorite = favorite
    )

    @Test
    fun statsRows_carryEveryFieldStatisticsUses() = runBlocking {
        dao.insertWorkout(
            workout(1_000L, distance = 10f, type = WorkoutType.LONG_RUN).copy(
                movingDuration = 1_500_000L,
                elevationGain = 42f,
                elevationSource = ElevationSource.GPS,
                avgCadence = 171f
            )
        )
        dao.insertWorkout(workout(2_000L, track = null))

        val rows = dao.getStatsRows()

        assertEquals(2, rows.size)
        assertEquals(
            WorkoutStatsRow(
                date = Date(1_000L),
                distance = 10f,
                duration = 1_800_000L,
                movingDuration = 1_500_000L,
                avgPace = 6f,
                calories = 300,
                type = WorkoutType.LONG_RUN,
                elevationGain = 42f,
                elevationSource = ElevationSource.GPS,
                avgCadence = 171f
            ),
            rows.first { it.date.time == 1_000L }
        )
    }

    @Test
    fun routePagingSource_listsTrackedWorkoutsFavoritesFirstExcludingCurrent() = runBlocking {
        val oldFavorite = dao.insertWorkout(workout(1_000L, favorite = true))
        val newer = dao.insertWorkout(workout(3_000L))
        dao.insertWorkout(workout(4_000L, track = null))
        val current = dao.insertWorkout(workout(5_000L))

        val page = dao.pagingSourceRoutes(excludeId = current)
            .load(PagingSource.LoadParams.Refresh(key = null, loadSize = 10, placeholdersEnabled = false))
            as PagingSource.LoadResult.Page

        assertEquals(listOf(oldFavorite, newer), page.data.map { it.id })
    }

    @Test
    fun pagingConfig_dropsPagesScrolledPast() {
        val config = WorkoutViewModel.PAGING_CONFIG
        assertNotEquals(androidx.paging.PagingConfig.MAX_SIZE_UNBOUNDED, config.maxSize)
        // Paging requires room for the visible page plus prefetch on both sides
        assertTrue(config.maxSize >= config.pageSize + 2 * config.prefetchDistance)
    }
}
