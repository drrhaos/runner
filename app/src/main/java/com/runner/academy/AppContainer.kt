package com.runner.academy

import android.content.Context
import android.os.SystemClock
import com.runner.academy.data.MetricsBackfill
import com.runner.academy.data.TrainingPlanRepository
import com.runner.academy.data.WorkoutDatabase
import com.runner.academy.data.WorkoutRepository
import com.runner.academy.data.GpsDiagnosticsStore
import com.runner.academy.debug.DebugSensors
import com.runner.academy.sensor.AndroidBle
import com.runner.academy.sensor.BleHeartRateSensor
import com.runner.academy.sensor.BleScanner
import com.runner.academy.sensor.HeartRateOverride
import com.runner.academy.sensor.HeartRateSensor
import com.runner.academy.sensor.PairedSensor
import com.runner.academy.sensor.PairedSensorStore
import com.runner.academy.sensor.SensorLease
import com.runner.academy.sensor.SensorScanner
import com.runner.academy.sensor.SensorType
import com.runner.academy.sensor.UnavailableHeartRateSensor
import com.runner.academy.sensor.UnavailableSensorScanner
import com.runner.academy.service.ActiveWorkoutStore
import com.runner.academy.util.UserPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * Manual DI graph for the app. Constructed once on [RunnerApplication].
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    /** Work that outlives screens (the metrics pass); lives as long as the process. */
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database: WorkoutDatabase by lazy { WorkoutDatabase.getDatabase(appContext) }

    val workoutRepository: WorkoutRepository by lazy {
        WorkoutRepository(database, gpsDiagnosticsStore, onDeferredSaved = { ids -> metricsBackfill.start(importedIds = ids) })
    }

    /** Derived metrics of rows saved without them; started on app start and after an import. */
    val metricsBackfill: MetricsBackfill by lazy {
        val activeWorkout = ActiveWorkoutStore(appContext)
        MetricsBackfill(
            database,
            applicationScope,
            isWorkoutActive = { withContext(Dispatchers.IO) { activeWorkout.isWorkoutActive() } }
        )
    }

    val trainingPlanRepository: TrainingPlanRepository by lazy {
        TrainingPlanRepository(
            templateDao = database.workoutTemplateDao(),
            planDao = database.trainingPlanDao(),
            scheduleDao = database.planScheduleDao()
        )
    }

    val userPreferences: UserPreferences by lazy { UserPreferences(appContext) }

    val pairedSensorStore: PairedSensorStore by lazy { PairedSensorStore(appContext) }

    /**
     * The debug build's simulated heart-rate sensor when its hidden switch is on; always null in
     * release. Replaces both the sensor and the pairing. Read once per process, on the first use
     * of a sensor (a small preferences file, cheap on the main thread).
     */
    private val heartRateOverride: HeartRateOverride? by lazy {
        DebugSensors.heartRateOverride(appContext, applicationScope)
    }

    /**
     * The paired sensors as screens and the service see them: [pairedSensorStore], or the
     * simulated pairing of the debug sensor (then the store is neither read nor written).
     * Change them with [pairSensor] and [forgetSensor], not through the store.
     */
    val pairedSensors: StateFlow<Map<SensorType, PairedSensor>>
        get() = heartRateOverride?.paired ?: pairedSensorStore.changes

    /** Pairs [sensor] (replacing the one of its type); ignored while the debug sensor simulates. */
    fun pairSensor(sensor: PairedSensor) {
        val override = heartRateOverride
        if (override != null) override.pair(sensor) else pairedSensorStore.put(sensor)
    }

    /** Forgets the sensor of [type]; while simulating, the simulated one. */
    fun forgetSensor(type: SensorType) {
        val override = heartRateOverride
        if (override != null) override.forget(type) else pairedSensorStore.remove(type)
    }

    /** Bluetooth LE and the BLE thread; null on a device without Bluetooth LE. */
    private val androidBle: AndroidBle? by lazy { AndroidBle.createOrNull(appContext) }

    private val bleHeartRateSensor: BleHeartRateSensor? by lazy {
        val ble = androidBle ?: return@lazy null
        BleHeartRateSensor(
            store = pairedSensorStore,
            link = ble.newLink(),
            scheduler = ble.scheduler,
            canConnect = ble::canConnect,
            bluetoothOn = { ble.bluetoothOn },
            nowMs = SystemClock::elapsedRealtime,
            wallMs = System::currentTimeMillis,
            scope = applicationScope
        )
    }

    /** The paired heart-rate sensor, shared through leases by screens and the workout service. */
    val heartRateSensor: HeartRateSensor
        get() = heartRateOverride?.sensor ?: bleHeartRateSensor ?: UnavailableHeartRateSensor

    /** Finding and checking a sensor to pair; a probe of the paired device holds the sensor off it. */
    val sensorScanner: SensorScanner by lazy {
        androidBle?.let { ble ->
            BleScanner(ble, holdOff = { address ->
                // The simulated sensor never talks to the device: the real one is not created
                val real = if (heartRateOverride == null) bleHeartRateSensor else null
                real?.holdOff(address) ?: SensorLease {}
            })
        } ?: UnavailableSensorScanner
    }

    val gpsDiagnosticsStore: GpsDiagnosticsStore by lazy {
        GpsDiagnosticsStore(
            dir = java.io.File(appContext.filesDir, GpsDiagnosticsStore.DIRECTORY),
            shareDir = java.io.File(appContext.cacheDir, GpsDiagnosticsStore.SHARE_DIRECTORY)
        )
    }
}

fun Context.appContainer(): AppContainer =
    (applicationContext as RunnerApplication).container
