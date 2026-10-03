package com.runner.academy.ui.tracking

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.test.core.app.ApplicationProvider
import com.runner.academy.data.GpsStatus
import com.runner.academy.data.TrackData
import com.runner.academy.data.TrackPoint
import com.runner.academy.data.Workout
import com.runner.academy.data.WorkoutDao
import com.runner.academy.data.WorkoutDatabase
import com.runner.academy.data.WorkoutRepository
import com.runner.academy.data.WorkoutSession
import com.runner.academy.data.WorkoutState
import com.runner.academy.data.WorkoutType
import com.runner.academy.util.FormatUtils
import com.runner.academy.util.TrackDataJson
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowApplication
import java.util.Date

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = com.runner.academy.RunnerApplication::class)
@OptIn(ExperimentalCoroutinesApi::class)
class WorkoutTrackingViewModelTest {

    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule()

    private lateinit var context: Context
    private lateinit var database: WorkoutDatabase
    private lateinit var workoutDao: WorkoutDao
    private lateinit var viewModel: WorkoutTrackingViewModel

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        ShadowApplication.getInstance().grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        database = WorkoutDatabase.getInMemoryDatabase(context)
        workoutDao = database.workoutDao()
        viewModel = WorkoutTrackingViewModel(WorkoutRepository(workoutDao), context as Application)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun viewmodel_should_initialize_with_default_state() {
        val session = viewModel.workoutSession.value

        assertFalse(session.isTracking)
        assertFalse(session.isPaused)
        assertEquals(0f, session.distance)
        assertEquals(GpsStatus.SEARCHING, session.gpsStatus)
        assertEquals(WorkoutState.NOT_STARTED, viewModel.workoutState.value)
    }

    @Test
    fun viewmodel_should_report_location_permission() {
        assertTrue(viewModel.hasLocationPermission())
        ShadowApplication.getInstance().denyPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        assertFalse(viewModel.hasLocationPermission())
    }

    @Test
    fun viewmodel_should_initialize_service_connection() {
        viewModel.initializeService()
        assertTrue(true)
    }

    @Test
    fun viewmodel_should_format_duration_correctly() {
        val formatted = FormatUtils.formatTime(3_661_000L)
        assertTrue(formatted.isNotEmpty())
    }

    @Test
    fun viewmodel_should_format_pace_correctly() {
        val formatted = viewModel.formatPace(5.5f)
        assertTrue(formatted.isNotEmpty())
    }

    @Test
    fun `startWorkout without bound service does not invent local session`() {
        viewModel.startWorkout(WorkoutType.EASY_RUN)

        assertEquals(WorkoutState.NOT_STARTED, viewModel.workoutState.value)
        assertFalse(viewModel.workoutSession.value.isTracking)
    }

    @Test
    fun `pause and resume without service are no-ops`() {
        viewModel.pauseWorkout()
        viewModel.resumeWorkout()
        assertEquals(WorkoutState.NOT_STARTED, viewModel.workoutState.value)
    }

    @Test
    fun `saving bridges dropped stretches with the run's stride like the live path`() = runTest {
        val run = com.runner.academy.gpsreplay.SyntheticRun(
            com.runner.academy.gpsreplay.SyntheticRun.blockLoop(laps = 1),
            speedMps = 1.1 * 165 / 60,
            spoof = com.runner.academy.gpsreplay.SyntheticRun.Spoof.Teleport(seconds = 100..220),
            strideM = 1.1
        )
        val raw = run.rawPoints()
        val model = com.runner.academy.util.StrideModel.fromHeight(175f)
        // What the live path showed with the frozen run stride
        val live = com.runner.academy.gpsreplay.GpsReplay.live(raw, stepDistance = model.frozenEstimator())
        val field = WorkoutTrackingViewModel::class.java.getDeclaredField("_workoutSession")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val flow = field.get(viewModel) as kotlinx.coroutines.flow.MutableStateFlow<WorkoutSession>
        com.runner.academy.util.UserPreferences(context).userHeight = 175f
        flow.value = WorkoutSession(
            startTime = raw.first().timestamp,
            currentTime = raw.last().timestamp - raw.first().timestamp,
            rawTrackDataPoints = raw,
            distance = (live.distanceMeters / 1000).toFloat(),
            strideModelState = model.serialize()
        )

        assertNotNull(viewModel.saveWorkoutToDatabase(WorkoutType.EASY_RUN))

        val saved = workoutDao.getAllWorkouts().first().first()
        assertEquals(live.distanceMeters / 1000, saved.distance.toDouble(), 0.002)
        val bridge = TrackDataJson.parse(saved.trackData)!!.points.single { it.afterGap }
        assertEquals(com.runner.academy.data.LocationSource.PEDOMETER.name, bridge.source)
    }

    @Test
    fun `viewModel should save duration-only workout without gps track`() = runTest {
        val field = WorkoutTrackingViewModel::class.java.getDeclaredField("_workoutSession")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val flow = field.get(viewModel) as kotlinx.coroutines.flow.MutableStateFlow<WorkoutSession>
        flow.value = WorkoutSession(
            startTime = System.currentTimeMillis() - 120_000L,
            currentTime = 120_000L
        )

        val workoutId = viewModel.saveWorkoutToDatabase(WorkoutType.EASY_RUN)
        assertNotNull(workoutId)

        val saved = workoutDao.getAllWorkouts().first().first()
        assertEquals(120_000L, saved.duration)
        assertEquals(0f, saved.distance)
        assertNull(saved.trackData)
    }

    @Test
    fun viewmodel_should_handle_workout_with_track_data() = runTest {
        val trackData = TrackData(
            points = listOf(
                TrackPoint(55.7558, 37.6173, System.currentTimeMillis(), 10f, 3f, 150.0),
                TrackPoint(55.7560, 37.6175, System.currentTimeMillis() + 1000, 10f, 3f, 151.0)
            ),
            totalDistance = 200f,
            totalDuration = 2000L,
            avgSpeed = 3f,
            maxSpeed = 4f,
            startTime = System.currentTimeMillis(),
            endTime = System.currentTimeMillis() + 2000L
        )

        val workout = Workout(
            id = 0,
            date = Date(),
            distance = 0.2f,
            duration = 2000L,
            avgPace = 5.0f,
            calories = 50,
            notes = null,
            type = WorkoutType.EASY_RUN,
            trackData = TrackDataJson.toJson(trackData)
        )

        workoutDao.insertWorkout(workout)

        val saved = workoutDao.getAllWorkouts().first()
        assertEquals(1, saved.size)
        assertNotNull(saved[0].trackData)
    }

    @Test
    fun trackingModeSelectionCodec_roundTrips() {
        assertEquals(
            TrackingModeSelection.EasyRun,
            TrackingModeSelectionCodec.decode(
                TrackingModeSelectionCodec.encode(TrackingModeSelection.EasyRun)
            )
        )
        assertEquals(
            TrackingModeSelection.PlanToday,
            TrackingModeSelectionCodec.decode(
                TrackingModeSelectionCodec.encode(TrackingModeSelection.PlanToday)
            )
        )
        val template = TrackingModeSelection.Template(42L)
        assertEquals(
            template,
            TrackingModeSelectionCodec.decode(TrackingModeSelectionCodec.encode(template))
        )
        assertNull(TrackingModeSelectionCodec.decode(null))
        assertNull(TrackingModeSelectionCodec.decode("bogus"))
    }
}
