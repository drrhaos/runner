package com.runner.academy.service

import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Looper
import androidx.core.app.ServiceCompat
import com.runner.academy.data.WorkoutType
import com.runner.academy.gpsreplay.SyntheticRun
import com.runner.academy.sensor.FakeHeartRateSensor
import com.runner.academy.sensor.PairedSensor
import com.runner.academy.sensor.SensorLink
import com.runner.academy.sensor.SensorType
import com.runner.academy.ui.tracking.LiveWorkoutBuilder
import com.runner.academy.util.CalorieProfile
import com.runner.academy.util.DerivationInput
import com.runner.academy.util.TrackSanitizer
import com.runner.academy.util.WorkoutDerivation
import com.runner.academy.sensor.HeartRateDisplay
import com.runner.academy.util.UserPreferences
import com.runner.academy.voice.FakeVoiceOutput
import com.runner.academy.voice.StatusKind
import com.runner.academy.voice.VoiceLine
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

/**
 * The heart-rate sensor in the workout service, with a [FakeHeartRateSensor]: the lease, the
 * points' `hr`, the restore and the foreground service types. 34: the connected-device type
 * applies there.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class WorkoutServiceHeartRateTest {

    private companion object {
        const val SEC = 1_000_000_000L
    }

    private val strap = PairedSensor(SensorType.HEART_RATE, "AA:BB:CC:DD:EE:FF", "HR Strap 10", 1L, null, null)
    private val sensor = FakeHeartRateSensor(SensorLink.CONNECTED)
    private val paired = MutableStateFlow(mapOf(SensorType.HEART_RATE to strap))

    /** Types of every `startForeground`, in order. */
    private val starts = mutableListOf<Int>()
    private var refuseConnectedDevice = false
    /** Every `startForeground` throws, location only included (a start from the background). */
    private var refuseAll = false

    /** The heart-rate clock of the service; starts at 1 000 s. */
    private var clockNanos = 1_000 * SEC
    /** What the voice said of the sensor (the voice is on): (second of [clockNanos], line). */
    private val said = mutableListOf<Pair<Int, StatusKind>>()
    private var dispatcher: CoroutineDispatcher = Dispatchers.Unconfined
    private val controllers = mutableListOf<ServiceController<WorkoutTrackingService>>()

    private val location = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
    private val both = location or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE

    private fun service(): WorkoutTrackingService {
        val controller = Robolectric.buildService(WorkoutTrackingService::class.java)
        controllers += controller
        val service = controller.get()
        service.heartRateSource = HeartRateSource(sensor, paired) { true }
        service.heartRateDispatcher = dispatcher
        service.heartRateClockNanos = { clockNanos }
        UserPreferences(RuntimeEnvironment.getApplication()).voiceFeedback = true
        service.voiceOutputFactory = {
            FakeVoiceOutput { line ->
                if (line is VoiceLine.Status && line.kind.name.startsWith("HR_")) said += (clockNanos / SEC).toInt() to line.kind
            }
        }
        service.foregroundStarter = { notification, types ->
            starts += types
            if (refuseAll) throw SecurityException("location in the background")
            if (refuseConnectedDevice && types and ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE != 0) {
                throw SecurityException("FOREGROUND_SERVICE_CONNECTED_DEVICE needs BLUETOOTH_CONNECT")
            }
            ServiceCompat.startForeground(service, WorkoutTrackingService.NOTIFICATION_ID, notification, types)
        }
        controller.create()
        return service
    }

    @After
    fun tearDown() {
        controllers.forEach { controller ->
            val service = controller.get()
            if (service.getCurrentSession().isTracking || service.getCurrentSession().isPaused) service.stopWorkout()
            controller.destroy()
        }
        ActiveWorkoutStore(RuntimeEnvironment.getApplication()).clear()
    }

    private fun idleMain() = shadowOf(Looper.getMainLooper()).idle()

    /** Waits for work posted from a background flow to reach the main thread. */
    private fun awaitMain(condition: () -> Boolean) {
        repeat(100) {
            idleMain()
            if (condition()) return
            Thread.sleep(20)
        }
        idleMain()
    }

    @Test
    fun `a workout with a paired sensor holds the workout lease and both service types`() {
        val service = service()
        // The screen hands its pre-start lease over when it sees the first running session:
        // by then the service must already hold its own
        var leasesAtPublish: List<String>? = null
        service.setSessionUpdateCallback { session ->
            if (session.isTracking && leasesAtPublish == null) leasesAtPublish = sensor.owners
        }
        service.startWorkout()

        assertEquals(listOf(HeartRateTracker.LEASE_OWNER), leasesAtPublish)
        assertEquals(listOf(HeartRateTracker.LEASE_OWNER), sensor.owners)
        assertEquals(listOf(both), starts)
    }

    @Test
    fun `without a paired sensor - no lease, location only`() {
        paired.value = emptyMap()
        val service = service()
        service.startWorkout()

        assertEquals(0, sensor.leaseCount)
        assertEquals(listOf(location), starts)
        assertNull(service.getCurrentSession().heartRateDisplay)
    }

    @Test
    fun `samples at 1 Hz and fixes - the points carry hr, the saved workout an average`() {
        val service = service()
        service.startWorkout()
        val raw = SyntheticRun(SyntheticRun.blockLoop(1)).rawPoints().take(150)
        val base = 9_000_000_000_000L
        val t0 = raw.first().timestamp
        for (fix in raw) {
            val at = base + (fix.timestamp - t0) * 1_000_000L
            sensor.emit(150, atNanos = at - 300_000_000L)
            service.updateLocation(TrackSanitizer.toLocation(fix).apply { elapsedRealtimeNanos = at })
        }
        idleMain()

        val session = service.getCurrentSession()
        val withHr = session.trackDataPoints.count { it.heartRate == 150 }
        assertTrue("points with hr: $withHr of ${session.trackDataPoints.size}", withHr > 100)
        assertTrue(session.rawTrackDataPoints.all { it.heartRate == 150 })

        val now = System.currentTimeMillis()
        val workout = LiveWorkoutBuilder.build(
            LiveWorkoutBuilder.stopped(session, now), WorkoutType.EASY_RUN, null, null, null,
            CalorieProfile(70f, CalorieProfile.Sex.MALE, null), now
        )!!
        val derived = WorkoutDerivation.derive(DerivationInput(workout.trackData, workout.type, workout.duration))
        assertEquals(150, derived.avgHeartRate)
        assertEquals(150, derived.maxHeartRate)
    }

    @Test
    fun `a manual pause keeps the connection, stop gives the lease back`() {
        val service = service()
        service.startWorkout()
        service.pauseWorkout()
        assertEquals(1, sensor.leaseCount)

        service.resumeWorkout()
        service.stopWorkout()
        assertEquals(0, sensor.leaseCount)
    }

    @Test
    fun `a restore from the checkpoint starts the tracker, stop releases it`() {
        // The first process: a workout whose checkpoint is on disk
        val first = service()
        first.startWorkout()
        val context = RuntimeEnvironment.getApplication()
        ActiveWorkoutStore(context).save(
            ActiveWorkoutCheckpoint.fromSession(
                first.getCurrentSession(), WorkoutType.EASY_RUN, null, null, null,
                lastLocationTime = 0L, lastUpdateTime = 0L
            )
        )
        controllers.removeAt(0).destroy()
        assertEquals(0, sensor.leaseCount)

        // The new process restores it
        val restored = service()
        restored.onStartCommand(Intent(context, WorkoutTrackingService::class.java).setAction(WorkoutTrackingService.ACTION_RESTORE_WORKOUT), 0, 1)

        assertTrue(restored.getCurrentSession().isTracking)
        assertEquals(listOf(HeartRateTracker.LEASE_OWNER), sensor.owners)
        assertEquals(both, starts.last())

        restored.stopWorkout()
        assertEquals(0, sensor.leaseCount)
    }

    @Test
    fun `a refused connected-device type falls back to location and the workout goes on without heart rate`() {
        refuseConnectedDevice = true
        val service = service()
        service.startWorkout()

        assertEquals(listOf(both, location), starts)
        assertEquals(0, sensor.leaseCount)
        assertTrue(service.getCurrentSession().isTracking)
        // Paired but not usable: lost on the tile, not "no sensor added"
        assertEquals(HeartRateDisplay.UNAVAILABLE, service.getCurrentSession().heartRateDisplay)

        // A pause keeps it so; it never speaks (no valid sample ever)
        service.pauseWorkout()
        service.resumeWorkout()
        clockNanos += 120 * SEC
        service.tickHeartRate()
        assertEquals(HeartRateDisplay.UNAVAILABLE, service.getCurrentSession().heartRateDisplay)
        assertEquals(emptyList<Pair<Int, StatusKind>>(), said)
    }

    @Test
    fun `a refusal of location only too is the restore's failure, not the sensor's`() {
        com.runner.academy.util.UserPreferences(RuntimeEnvironment.getApplication()).gpsDiagnostics = true
        val first = service()
        first.startWorkout()
        val context = RuntimeEnvironment.getApplication()
        val startTime = first.getCurrentSession().startTime
        ActiveWorkoutStore(context).save(
            ActiveWorkoutCheckpoint.fromSession(
                first.getCurrentSession(), WorkoutType.EASY_RUN, null, null, null,
                lastLocationTime = 0L, lastUpdateTime = 0L
            )
        )
        controllers.removeAt(0).destroy()

        refuseAll = true
        starts.clear()
        val restored = service()
        restored.onStartCommand(
            Intent(context, WorkoutTrackingService::class.java).setAction(WorkoutTrackingService.ACTION_RESTORE_WORKOUT), 0, 1
        )

        assertEquals(listOf(both, location), starts)
        assertEquals(0, sensor.leaseCount)
        val lines = readDiagnostics(startTime, expected = "restore_failed")
        assertTrue(lines.any { it.contains("restore_failed") })
        assertTrue(lines.none { it.contains(WorkoutTrackingService.FGS_NO_CONNECTED_DEVICE) })
    }

    /** Valid samples once a second from now for [seconds] s, the tile ticked each second. */
    private fun WorkoutTrackingService.wear(seconds: Int) {
        repeat(seconds) {
            sensor.emit(150, atNanos = clockNanos)
            tickHeartRate()
            clockNanos += SEC
        }
    }

    /**
     * Ticks once a second without valid samples, through second [untilSec] of the clock;
     * [noContact]: "no contact" samples come meanwhile.
     */
    private fun WorkoutTrackingService.tickUntil(untilSec: Int, noContact: Boolean = false) {
        while (clockNanos / SEC <= untilSec) {
            if (noContact) sensor.emit(null, atNanos = clockNanos)
            tickHeartRate()
            clockNanos += SEC
        }
    }

    @Test
    fun `the voice says lost 30 s after the last valid sample, not before`() {
        val service = service()
        service.startWorkout()
        service.wear(10)
        // The last valid sample at 1 009 s; the strap goes silent
        service.tickUntil(1_038)
        assertEquals(emptyList<Pair<Int, StatusKind>>(), said)

        service.tickUntil(1_039)
        assertEquals(listOf(1_039 to StatusKind.HR_LOST), said)

        service.wear(1)
        assertEquals(listOf(1_039 to StatusKind.HR_LOST, 1_040 to StatusKind.HR_CONNECTED), said)
    }

    @Test
    fun `a 29 s gap in the samples stays silent`() {
        val service = service()
        service.startWorkout()
        service.wear(10)
        service.tickUntil(1_037)
        service.wear(5)

        assertEquals(emptyList<Pair<Int, StatusKind>>(), said)
    }

    @Test
    fun `a paired strap never worn never speaks`() {
        val service = service()
        service.startWorkout()
        // Connected, no contact the whole time
        sensor.setLink(SensorLink.CONNECTED)
        service.tickUntil(1_300, noContact = true)

        assertEquals(emptyList<Pair<Int, StatusKind>>(), said)
        assertTrue(service.getCurrentSession().heartRateDisplay!!.bpm == null)
    }

    @Test
    fun `a sensor paired mid-workout restarts the foreground with both types and joins`() {
        paired.value = emptyMap()
        val service = service()
        service.startWorkout()
        assertEquals(0, sensor.leaseCount)

        paired.value = mapOf(SensorType.HEART_RATE to strap)
        awaitMain { sensor.leaseCount == 1 }

        assertEquals(listOf(location, both), starts)
        assertEquals(listOf(HeartRateTracker.LEASE_OWNER), sensor.owners)

        // Forgotten: location only again, the lease goes
        paired.value = emptyMap()
        awaitMain { sensor.leaseCount == 0 }
        assertEquals(listOf(location, both, location), starts)
    }

    /**
     * Stops the workout (the recorder writes in batches; the stop closes the file) and reads its
     * diagnostics once [expected] is there.
     */
    private fun diagnosticsLines(service: WorkoutTrackingService, expected: String): List<String> {
        val startTime = service.getCurrentSession().startTime
        service.stopWorkout()
        return readDiagnostics(startTime, expected)
    }

    /** The diagnostics file of the workout started at [startTime], once [expected] is there. */
    private fun readDiagnostics(startTime: Long, expected: String): List<String> {
        val store = (RuntimeEnvironment.getApplication() as com.runner.academy.RunnerApplication).container.gpsDiagnosticsStore
        val file = store.sessionFile(startTime)
        repeat(100) {
            if (file.isFile && file.readText().contains(expected)) return file.readLines()
            Thread.sleep(20)
        }
        return if (file.isFile) file.readLines() else emptyList()
    }

    @Test
    fun `sensor events and the foreground refusal go to the diagnostics log`() {
        com.runner.academy.util.UserPreferences(RuntimeEnvironment.getApplication()).gpsDiagnostics = true
        refuseConnectedDevice = true
        val service = service()
        service.startWorkout()
        // Refused: no sensor in this workout, its events are not forwarded any more
        assertTrue(diagnosticsLines(service, "fgs_no_connected_device").any {
            it.contains("\"sensor\":\"hr\"") && it.contains("\"event\":\"fgs_no_connected_device\"")
        })
    }

    @Test
    fun `the sensor's link events are forwarded while the workout uses it`() {
        com.runner.academy.util.UserPreferences(RuntimeEnvironment.getApplication()).gpsDiagnostics = true
        val service = service()
        service.startWorkout()
        sensor.diag(com.runner.academy.sensor.SensorDiagEvent(com.runner.academy.sensor.SensorDiagEvent.Kind.DISCONNECTED, 8))
        sensor.diag(com.runner.academy.sensor.SensorDiagEvent(com.runner.academy.sensor.SensorDiagEvent.Kind.BATTERY, 80))
        // Recorded on the main thread
        idleMain()

        val lines = diagnosticsLines(service, "battery")
        assertTrue(lines.any { it.contains("\"event\":\"disconnected\"") && it.contains("\"status\":8") })
        assertTrue(lines.any { it.contains("\"event\":\"battery\"") && it.contains("\"value\":80") })
    }

    @Test
    fun `rssi is read every 30 s only while the diagnostics record`() {
        val scheduler = TestCoroutineScheduler()
        dispatcher = StandardTestDispatcher(scheduler)
        val prefs = com.runner.academy.util.UserPreferences(RuntimeEnvironment.getApplication())
        prefs.gpsDiagnostics = false
        val quiet = service()
        quiet.startWorkout()
        scheduler.advanceTimeBy(95_000L)
        assertEquals(0, sensor.rssiReads)
        quiet.stopWorkout()

        prefs.gpsDiagnostics = true
        val logging = service()
        logging.startWorkout()
        scheduler.advanceTimeBy(29_000L)
        assertEquals(0, sensor.rssiReads)
        scheduler.advanceTimeBy(66_000L)
        assertEquals(3, sensor.rssiReads)
    }

    @Test
    fun `the tile tick puts the display on the session`() {
        val service = service()
        service.startWorkout()
        sensor.emit(152, atNanos = clockNanos)

        awaitMain { service.getCurrentSession().heartRateDisplay?.bpm == 152 }

        assertEquals(152, service.getCurrentSession().heartRateDisplay?.bpm)
    }
}
