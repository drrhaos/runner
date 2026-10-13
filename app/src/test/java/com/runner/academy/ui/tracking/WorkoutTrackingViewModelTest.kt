package com.runner.academy.ui.tracking

import android.Manifest
import android.app.Application
import android.content.Context
import android.os.Looper
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
import com.runner.academy.sensor.FakeHeartRateSensor
import com.runner.academy.sensor.HeartRateDisplay
import com.runner.academy.sensor.PairedSensor
import com.runner.academy.sensor.SensorLink
import com.runner.academy.sensor.SensorType
import com.runner.academy.util.FormatUtils
import com.runner.academy.util.TrackDataJson
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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
import org.robolectric.Shadows.shadowOf
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
    private val pairedSensors = MutableStateFlow<Map<SensorType, PairedSensor>>(emptyMap())
    private val hrSensor = FakeHeartRateSensor()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        ShadowApplication.getInstance().grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        database = WorkoutDatabase.getInMemoryDatabase(context)
        workoutDao = database.workoutDao()
        viewModel = WorkoutTrackingViewModel(
            WorkoutRepository(database), context as Application, pairedSensors, hrSensor, clockNanos = { nowNanos }
        )
    }

    private var nowNanos = 1_000_000_000_000L
    private val strap = PairedSensor(SensorType.HEART_RATE, "AA:BB", "HR Strap 10", 1L, null, null)

    @Test
    fun `before the start a paired sensor is held by the screen and shown`() {
        pairedSensors.value = mapOf(SensorType.HEART_RATE to strap)
        hrSensor.setLink(SensorLink.CONNECTING)
        viewModel.holdHeartRateBeforeStart()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf(WorkoutTrackingViewModel.PRESTART_LEASE), hrSensor.owners)
        assertEquals(HeartRateDisplay.LinkIcon.SEARCHING, viewModel.heartRateDisplay.value!!.link)
        assertNull(viewModel.heartRateDisplay.value!!.bpm)

        hrSensor.setLink(SensorLink.CONNECTED)
        hrSensor.emit(141, atNanos = nowNanos)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(141, viewModel.heartRateDisplay.value!!.bpm)
        assertEquals(HeartRateDisplay.LinkIcon.CONNECTED, viewModel.heartRateDisplay.value!!.link)

        // A second hold (onStart again) keeps one lease
        viewModel.holdHeartRateBeforeStart()
        assertEquals(1, hrSensor.leaseCount)

        viewModel.releaseHeartRateBeforeStart()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, hrSensor.leaseCount)
    }

    @Test
    fun `the pre-start value goes grey on the one second tick`() {
        pairedSensors.value = mapOf(SensorType.HEART_RATE to strap)
        hrSensor.setLink(SensorLink.CONNECTED)
        viewModel.holdHeartRateBeforeStart()
        hrSensor.emit(141, atNanos = nowNanos)
        shadowOf(Looper.getMainLooper()).idle()

        nowNanos += 8_000_000_000L
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
        assertEquals(HeartRateDisplay.Freshness.STALE, viewModel.heartRateDisplay.value!!.freshness)
        viewModel.releaseHeartRateBeforeStart()
    }

    @Test
    fun `without a paired sensor nothing is held and the tile has no sensor`() {
        viewModel.holdHeartRateBeforeStart()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(0, hrSensor.leaseCount)
        assertNull(viewModel.heartRateDisplay.value)
    }

    @Test
    fun `the screen lets its lease go only once the service runs the workout`() {
        pairedSensors.value = mapOf(SensorType.HEART_RATE to strap)
        viewModel.holdHeartRateBeforeStart()
        // The countdown and the service start: the screen still holds
        viewModel.onSessionFromService(WorkoutSession())
        assertEquals(listOf(WorkoutTrackingViewModel.PRESTART_LEASE), hrSensor.owners)

        // The service takes "workout" before it publishes the running session
        hrSensor.acquire("workout")
        val display = HeartRateDisplay(150, HeartRateDisplay.Freshness.FRESH, HeartRateDisplay.LinkIcon.CONNECTED)
        viewModel.onSessionFromService(WorkoutSession(isTracking = true, heartRateDisplay = display))
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf("workout"), hrSensor.owners)
        assertEquals(WorkoutState.RUNNING, viewModel.workoutState.value)
        // During the workout the tile is the service's
        assertEquals(display, viewModel.heartRateDisplay.value)
    }

    @Test
    fun `idle under the screen's lease is searching, never a red lost`() {
        pairedSensors.value = mapOf(SensorType.HEART_RATE to strap)
        hrSensor.setLink(SensorLink.IDLE)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(HeartRateDisplay.LinkIcon.SEARCHING, viewModel.heartRateDisplay.value!!.link)

        viewModel.holdHeartRateBeforeStart()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(HeartRateDisplay.LinkIcon.SEARCHING, viewModel.heartRateDisplay.value!!.link)
        viewModel.releaseHeartRateBeforeStart()
    }

    @Test
    fun `after the workout the screen still seen takes its lease again`() {
        pairedSensors.value = mapOf(SensorType.HEART_RATE to strap)
        viewModel.holdHeartRateBeforeStart()
        hrSensor.acquire("workout").let { workout ->
            viewModel.onSessionFromService(WorkoutSession(isTracking = true))
            assertEquals(listOf("workout"), hrSensor.owners)
            workout.release()
        }

        viewModel.resetWorkout()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(WorkoutTrackingViewModel.PRESTART_LEASE), hrSensor.owners)

        // Not after the screen stopped
        viewModel.releaseHeartRateBeforeStart()
        viewModel.onSessionFromService(WorkoutSession())
        assertEquals(0, hrSensor.leaseCount)
    }

    @Test
    fun `during a workout no pre-start lease is taken`() {
        pairedSensors.value = mapOf(SensorType.HEART_RATE to strap)
        viewModel.onSessionFromService(WorkoutSession(isTracking = true))
        viewModel.holdHeartRateBeforeStart()

        assertEquals(0, hrSensor.leaseCount)
    }

    @Test
    fun heart_rate_tile_opens_devices_without_a_sensor() {
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(HeartRateTile.NO_SENSOR, viewModel.heartRateTile.value)
        assertTrue(viewModel.heartRateTile.value.opensDevices)
    }

    @Test
    fun heart_rate_tile_follows_the_paired_sensor_and_its_link() {
        pairedSensors.value = mapOf(
            SensorType.HEART_RATE to PairedSensor(SensorType.HEART_RATE, "AA:BB", "HR Strap 10", 1L, null, null)
        )
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(HeartRateTile.DISCONNECTED, viewModel.heartRateTile.value)
        assertTrue(viewModel.heartRateTile.value.opensDevices)

        hrSensor.setLink(SensorLink.CONNECTED)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(HeartRateTile.CONNECTED, viewModel.heartRateTile.value)
        assertFalse(viewModel.heartRateTile.value.opensDevices)

        hrSensor.setLink(SensorLink.SEARCHING)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(HeartRateTile.DISCONNECTED, viewModel.heartRateTile.value)
        assertTrue(viewModel.heartRateTile.value.opensDevices)
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
        // The height changed after the start: the run's frozen prior must still be used
        com.runner.academy.util.UserPreferences(context).userHeight = 190f
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
        // No auto-pause yet: all of the time is moving
        assertEquals(120_000L, saved.movingDuration)
        assertEquals(0f, saved.distance)
        assertEquals(0f, saved.avgPace)
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
            movingDuration = 2000L,
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
