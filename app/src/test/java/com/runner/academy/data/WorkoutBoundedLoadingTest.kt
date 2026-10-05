package com.runner.academy.data

import android.content.Context
import androidx.paging.PagingSource
import androidx.room.Room
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.core.app.ApplicationProvider
import com.runner.academy.ui.workout.WorkoutViewModel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Date
import java.util.concurrent.Executor

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

    private fun <T : Any> firstPage(source: PagingSource<Int, T>): List<T> = runBlocking {
        (source.load(PagingSource.LoadParams.Refresh(key = null, loadSize = 10, placeholdersEnabled = false))
            as PagingSource.LoadResult.Page).data
    }

    @Test
    fun routePagingSource_listsTrackedWorkoutsFavoritesFirstExcludingCurrent() = runBlocking {
        val oldFavorite = dao.insertWorkout(workout(1_000L, favorite = true))
        val newer = dao.insertWorkout(workout(3_000L))
        dao.insertWorkout(workout(4_000L, track = null))
        val current = dao.insertWorkout(workout(5_000L))

        val page = firstPage(dao.pagingSourceRoutes(excludeId = current))

        assertEquals(listOf(oldFavorite, newer), page.map { it.id })
    }

    @Test
    fun listPages_carryEveryFieldTheListShows() = runBlocking {
        val tracked = dao.insertWorkout(
            workout(2_000L, distance = 10f, type = WorkoutType.LONG_RUN, favorite = true).copy(
                movingDuration = 1_500_000L,
                notes = "n",
                routePreview = "1|2|S:1,1;1,1",
                metricsVersion = 2
            )
        )
        val manual = dao.insertWorkout(workout(1_000L, track = null))

        val page = firstPage(dao.pagingSourceAll())

        assertEquals(
            listOf(
                WorkoutListItem(
                    id = tracked,
                    date = Date(2_000L),
                    distance = 10f,
                    duration = 1_800_000L,
                    movingDuration = 1_500_000L,
                    avgPace = 6f,
                    calories = 300,
                    notes = "n",
                    type = WorkoutType.LONG_RUN,
                    isFavorite = true,
                    hasTrack = true,
                    routePreview = "1|2|S:1,1;1,1",
                    metricsVersion = 2
                ),
                WorkoutListItem(
                    id = manual,
                    date = Date(1_000L),
                    distance = 5f,
                    duration = 1_800_000L,
                    movingDuration = 1_800_000L,
                    avgPace = 6f,
                    calories = 300,
                    notes = null,
                    type = WorkoutType.EASY_RUN,
                    isFavorite = false,
                    hasTrack = false,
                    routePreview = null,
                    metricsVersion = 0
                )
            ),
            page
        )
        assertEquals(listOf(tracked), firstPage(dao.pagingSourceFavorites()).map { it.id })
    }

    @Test
    fun listItem_hasNoTrackField() {
        val fields = WorkoutListItem::class.java.declaredFields.map { it.name }
        assertFalse(fields.toString(), fields.any { it.contains("trackData", ignoreCase = true) })
    }

    @Test
    fun pageQueries_neverSelectTheTrack() {
        val queries = mutableListOf<Pair<String, List<Any?>>>()
        val traced = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), WorkoutDatabase::class.java)
            .setQueryCallback({ sql, args -> synchronized(queries) { queries.add(sql to args.toList()) } }, Executor { it.run() })
            .allowMainThreadQueries()
            .build()
        try {
            val workouts = traced.workoutDao()
            val id = runBlocking { workouts.insertWorkout(workout(1_000L, track = "{\"points\":[]}", favorite = true)) }
            synchronized(queries) { queries.clear() }

            assertEquals(1, firstPage(workouts.pagingSourceAll()).size)
            assertEquals(1, firstPage(workouts.pagingSourceFavorites()).size)
            assertEquals(1, firstPage(workouts.pagingSourceRoutes(excludeId = id + 1)).size)

            val selects = synchronized(queries) { queries.toList() }
                .filter { (sql, _) -> sql.trimStart().startsWith("SELECT", ignoreCase = true) && "workouts" in sql }
            assertTrue(selects.toString(), selects.size >= 3)
            for ((sql, args) in selects) {
                traced.openHelper.readableDatabase.query(SimpleSQLiteQuery(sql, args.toTypedArray())).use { cursor ->
                    assertFalse(sql, cursor.columnNames.any { it.equals("trackData", ignoreCase = true) })
                }
            }
        } finally {
            traced.close()
        }
    }

    @Test
    fun trackData_isReadByIdAlone() = runBlocking {
        val id = dao.insertWorkout(workout(1_000L, track = "{\"points\":[1]}"))
        val manual = dao.insertWorkout(workout(2_000L, track = null))

        assertEquals("{\"points\":[1]}", dao.getTrackData(id))
        assertNull(dao.getTrackData(manual))
        assertNull(dao.getTrackData(id + manual + 1))
    }

    @Test
    fun pagingConfig_dropsPagesScrolledPast() {
        val config = WorkoutViewModel.PAGING_CONFIG
        assertNotEquals(androidx.paging.PagingConfig.MAX_SIZE_UNBOUNDED, config.maxSize)
        // Paging requires room for the visible page plus prefetch on both sides
        assertTrue(config.maxSize >= config.pageSize + 2 * config.prefetchDistance)
    }
}
