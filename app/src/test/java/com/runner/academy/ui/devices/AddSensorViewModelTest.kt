package com.runner.academy.ui.devices

import com.runner.academy.sensor.FakeSensorScanner
import com.runner.academy.sensor.FoundSensor
import com.runner.academy.sensor.PairedSensor
import com.runner.academy.sensor.ProbeFailure
import com.runner.academy.sensor.ProbeResult
import com.runner.academy.sensor.ScanException
import com.runner.academy.sensor.ScanFailure
import com.runner.academy.sensor.SensorPermissionPolicy
import com.runner.academy.sensor.SensorType
import com.runner.academy.ui.devices.AddSensorStep.Paired
import com.runner.academy.ui.devices.AddSensorStep.Permission
import com.runner.academy.ui.devices.AddSensorStep.ProbeFailed
import com.runner.academy.ui.devices.AddSensorStep.Probing
import com.runner.academy.ui.devices.AddSensorStep.Results
import com.runner.academy.ui.devices.AddSensorStep.ScanFailed
import com.runner.academy.ui.devices.AddSensorStep.Scanning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AddSensorViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val scanner = FakeSensorScanner()
    private var paired: PairedSensor? = null
    private val saved = mutableListOf<PairedSensor>()

    private val strap = FoundSensor("AA:01", "HR Strap 10", -55)
    private val chestStrap = FoundSensor("AA:02", null, -80)

    private val all31 = setOf(SensorPermissionPolicy.SCAN, SensorPermissionPolicy.CONNECT)
    private val fine30 = setOf(SensorPermissionPolicy.FINE_LOCATION)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.viewModel(sdk: Int = 34) = AddSensorViewModel(
        scanner = scanner,
        sdkInt = sdk,
        pairedSensor = { paired },
        pair = { saved += it; paired = it },
        nowMs = { testScheduler.currentTime },
        wallMs = { 1_700_000_000_000L }
    )

    private val AddSensorViewModel.step get() = state.value

    // -- Step 1: permission --

    @Test
    fun `no permission on 31+ asks for nearby devices`() = runTest(dispatcher) {
        val vm = viewModel(sdk = 31)
        vm.check(granted = emptySet(), locationOn = true)

        assertEquals(Permission(PermissionPrompt.RATIONALE_31), vm.step)
        assertEquals(0, scanner.scansStarted)
    }

    @Test
    fun `no permission on 30 explains the location`() = runTest(dispatcher) {
        val vm = viewModel(sdk = 30)
        vm.check(granted = emptySet(), locationOn = true)

        assertEquals(Permission(PermissionPrompt.RATIONALE_30), vm.step)
    }

    @Test
    fun `location switched off on 30 asks to turn it on, and a resume with it on scans`() = runTest(dispatcher) {
        val vm = viewModel(sdk = 30)
        vm.check(granted = fine30, locationOn = false)
        assertEquals(Permission(PermissionPrompt.LOCATION_OFF), vm.step)

        vm.check(granted = fine30, locationOn = true)
        runCurrent()
        assertTrue(vm.step is Scanning)
        vm.cancelAll()
    }

    @Test
    fun `a refusal that cannot be asked again points to the settings, until granted there`() = runTest(dispatcher) {
        val vm = viewModel(sdk = 34)
        vm.check(emptySet(), true)

        vm.onPermissionResult(emptySet(), true, canAskAgain = true)
        assertEquals(Permission(PermissionPrompt.RATIONALE_31), vm.step)

        vm.onPermissionResult(emptySet(), true, canAskAgain = false)
        assertEquals(Permission(PermissionPrompt.DENIED), vm.step)
        vm.check(emptySet(), true)
        assertEquals(Permission(PermissionPrompt.DENIED), vm.step)

        vm.check(all31, true)
        runCurrent()
        assertTrue(vm.step is Scanning)
        vm.cancelAll()
    }

    @Test
    fun `a resume while scanning does not restart the scan`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.check(all31, true)
        runCurrent()
        vm.check(all31, true)
        runCurrent()

        assertEquals(1, scanner.scansStarted)
        vm.cancelAll()
    }

    // -- Step 2-3: scan, list, hints --

    @Test
    fun `the scan counts down and grows the list in order`() = runTest(dispatcher) {
        scanner.scans += FakeSensorScanner.finding(strap, chestStrap)
        val vm = viewModel()
        vm.check(all31, true)
        runCurrent()
        assertEquals(Scanning(emptyList(), 20_000L), vm.step)

        advanceTimeBy(1_001L)
        runCurrent()
        val one = vm.step as Scanning
        assertEquals(listOf(strap), one.found.map { it.sensor })
        assertEquals(19_000L, one.msLeft)

        advanceTimeBy(1_000L)
        runCurrent()
        val two = vm.step as Scanning
        assertEquals(listOf(strap, chestStrap), two.found.map { it.sensor })
        assertEquals(listOf(SignalLevel.EXCELLENT, SignalLevel.WEAK), two.found.map { it.signal })
        assertEquals(18_000L, two.msLeft)

        advanceUntilIdle()
        val done = vm.step as Results
        assertEquals(listOf(strap, chestStrap), done.found.map { it.sensor })
        assertFalse(done.hintsOpen)
        assertEquals(0, done.retryInSeconds)
    }

    @Test
    fun `an empty scan opens the hints at once`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.check(all31, true)
        advanceUntilIdle()

        assertEquals(Results(emptyList(), hintsOpen = true, retryInSeconds = 0), vm.step)
    }

    @Test
    fun `the hints open on request after a scan with finds`() = runTest(dispatcher) {
        scanner.scans += FakeSensorScanner.finding(strap)
        val vm = viewModel()
        vm.check(all31, true)
        advanceUntilIdle()

        vm.showHints()

        assertTrue((vm.step as Results).hintsOpen)
    }

    @Test
    fun `the paired device is marked in the list`() = runTest(dispatcher) {
        paired = PairedSensor(SensorType.HEART_RATE, strap.address, "HR Strap 10", 1L, null, null)
        scanner.scans += FakeSensorScanner.finding(strap, chestStrap)
        val vm = viewModel()
        vm.check(all31, true)
        advanceUntilIdle()

        assertEquals(listOf(true, false), (vm.step as Results).found.map { it.alreadyPaired })
    }

    // -- Step 4: retry and the start limit --

    @Test
    fun `a scan that must wait counts down by seconds and then starts by itself`() = runTest(dispatcher) {
        scanner.waitMs = 2_500L
        val vm = viewModel()
        vm.check(all31, true)
        runCurrent()
        assertEquals(3, (vm.step as Results).retryInSeconds)
        assertEquals(0, scanner.scansStarted)

        advanceTimeBy(501L)
        runCurrent()
        assertEquals(2, (vm.step as Results).retryInSeconds)

        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(1, (vm.step as Results).retryInSeconds)

        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(1, scanner.scansStarted)
        assertTrue(vm.step is Scanning)
        vm.cancelAll()
    }

    @Test
    fun `too frequent is waited out the same way`() = runTest(dispatcher) {
        scanner.scans += FakeSensorScanner.failing(ScanException(ScanFailure.TOO_FREQUENT, waitMs = 1_000L))
        val vm = viewModel()
        vm.check(all31, true)
        runCurrent()
        assertEquals(1, (vm.step as Results).retryInSeconds)

        advanceTimeBy(1_001L)
        runCurrent()
        assertEquals(2, scanner.scansStarted)
        assertTrue(vm.step is Scanning)
        vm.cancelAll()
    }

    @Test
    fun `search again scans again`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.check(all31, true)
        advanceUntilIdle()

        vm.scanAgain()
        runCurrent()

        assertEquals(2, scanner.scansStarted)
        assertTrue(vm.step is Scanning)
        vm.cancelAll()
    }

    // -- Scan errors --

    @Test
    fun `scan errors`() = runTest(dispatcher) {
        fun after(failure: ScanException): AddSensorStep {
            scanner.scans += FakeSensorScanner.failing(failure)
            val vm = viewModel()
            vm.check(all31, true)
            runCurrent()
            return vm.step
        }

        assertEquals(ScanFailed(ScanFailure.UNSUPPORTED, null), after(ScanException(ScanFailure.UNSUPPORTED)))
        assertEquals(Permission(PermissionPrompt.RATIONALE_31), after(ScanException(ScanFailure.NO_PERMISSION)))
        assertEquals(Permission(PermissionPrompt.LOCATION_OFF), after(ScanException(ScanFailure.LOCATION_OFF)))
        assertEquals(ScanFailed(ScanFailure.BLUETOOTH_OFF, null), after(ScanException(ScanFailure.BLUETOOTH_OFF)))
        assertEquals(ScanFailed(ScanFailure.FAILED, 2), after(ScanException(ScanFailure.FAILED, errorCode = 2)))
    }

    @Test
    fun `bluetooth turned on after a scan error scans by itself`() = runTest(dispatcher) {
        scanner.scans += FakeSensorScanner.failing(ScanException(ScanFailure.BLUETOOTH_OFF))
        val vm = viewModel()
        vm.check(all31, true)
        runCurrent()

        vm.onBluetoothEnabled()
        runCurrent()

        assertTrue(vm.step is Scanning)
        vm.cancelAll()
    }

    // -- Step 5-7: choose, probe, result --

    @Test
    fun `replacing another paired device needs a confirmation, the same one does not`() = runTest(dispatcher) {
        val vm = viewModel()
        assertNull(vm.replaces(strap))

        paired = PairedSensor(SensorType.HEART_RATE, strap.address, "HR Strap 7", 1L, null, null)
        assertNull(vm.replaces(strap))
        assertEquals("HR Strap 7", vm.replaces(chestStrap)?.name)
    }

    @Test
    fun `choosing stops the scan and a good probe pairs with the scanned name`() = runTest(dispatcher) {
        scanner.scans += FakeSensorScanner.finding(strap)
        val vm = viewModel()
        vm.check(all31, true)
        advanceTimeBy(1_001L)
        runCurrent()

        vm.probe(strap)
        runCurrent()
        assertEquals(Probing(strap), vm.step)
        assertEquals(listOf(strap.address), scanner.probed)

        advanceTimeBy(30_000L)
        runCurrent()
        assertEquals(Probing(strap), vm.step)

        scanner.probeResult.complete(ProbeResult.Ok(name = "Strap stack", batteryPercent = 90))
        runCurrent()

        assertEquals(Paired("HR Strap 10"), vm.step)
        assertEquals(
            listOf(PairedSensor(SensorType.HEART_RATE, strap.address, "HR Strap 10", 1_700_000_000_000L, null, 90)),
            saved
        )
    }

    @Test
    fun `a device without an advertised name takes the probed one`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.probe(chestStrap)
        scanner.probeResult.complete(ProbeResult.Ok(name = "Run Watch", batteryPercent = null))
        runCurrent()

        assertEquals(Paired("Run Watch"), vm.step)
        assertEquals("Run Watch", saved.single().name)
    }

    @Test
    fun `replacing pairs the new device in place of the old`() = runTest(dispatcher) {
        paired = PairedSensor(SensorType.HEART_RATE, "OLD", "HR Strap 7", 1L, null, null)
        val vm = viewModel()
        vm.probe(strap)
        scanner.probeResult.complete(ProbeResult.Ok(null, null))
        runCurrent()

        assertEquals(strap.address, paired?.address)
    }

    @Test
    fun `probe failures, retry and back to the list`() = runTest(dispatcher) {
        scanner.scans += FakeSensorScanner.finding(strap)
        val vm = viewModel()
        vm.check(all31, true)
        advanceUntilIdle()

        for (reason in listOf(ProbeFailure.UNREACHABLE, ProbeFailure.NOT_HEART_RATE_SENSOR, ProbeFailure.BLUETOOTH_OFF)) {
            scanner.probeResult = kotlinx.coroutines.CompletableDeferred(ProbeResult.Failed(reason))
            vm.probe(strap)
            runCurrent()
            assertEquals(ProbeFailed(strap, reason), vm.step)
        }

        scanner.probeResult = kotlinx.coroutines.CompletableDeferred()
        vm.retryProbe()
        runCurrent()
        assertEquals(Probing(strap), vm.step)

        vm.cancelProbe()
        runCurrent()
        assertEquals(listOf(strap), (vm.step as Results).found.map { it.sensor })
        assertTrue(saved.isEmpty())

        scanner.probeResult = kotlinx.coroutines.CompletableDeferred(ProbeResult.Failed(ProbeFailure.UNREACHABLE))
        vm.probe(strap)
        runCurrent()
        vm.backToList()
        assertTrue(vm.step is Results)
    }

    @Test
    fun `bluetooth turned on after a failed probe probes again`() = runTest(dispatcher) {
        val vm = viewModel()
        scanner.probeResult = kotlinx.coroutines.CompletableDeferred(ProbeResult.Failed(ProbeFailure.BLUETOOTH_OFF))
        vm.probe(strap)
        runCurrent()

        scanner.probeResult = kotlinx.coroutines.CompletableDeferred()
        vm.onBluetoothEnabled()
        runCurrent()

        assertEquals(Probing(strap), vm.step)
        assertEquals(2, scanner.probed.size)
        vm.cancelAll()
    }

    @Test
    fun `a probe without permission goes back to step 1`() = runTest(dispatcher) {
        val vm = viewModel(sdk = 31)
        scanner.probeResult.complete(ProbeResult.Failed(ProbeFailure.NO_PERMISSION))
        vm.probe(strap)
        runCurrent()

        assertEquals(Permission(PermissionPrompt.RATIONALE_31), vm.step)
    }

    // -- Announcements --

    @Test
    fun `announces the end of a scan, the start and the outcome of a probe, nothing else`() {
        val scanning = Scanning(emptyList(), 10_000L)
        val found = listOf(FoundRow(strap, SignalLevel.EXCELLENT, false))

        assertEquals(AddSensorAnnouncement.Found(1), AddSensorAnnouncement.of(scanning, Results(found, false, 0)))
        assertEquals(AddSensorAnnouncement.NothingFound, AddSensorAnnouncement.of(scanning, Results(emptyList(), true, 0)))
        assertEquals(AddSensorAnnouncement.Probing(strap), AddSensorAnnouncement.of(Results(found, false, 0), Probing(strap)))
        assertEquals(
            AddSensorAnnouncement.ProbeFailed(strap, ProbeFailure.UNREACHABLE),
            AddSensorAnnouncement.of(Probing(strap), ProbeFailed(strap, ProbeFailure.UNREACHABLE))
        )
        assertNull(AddSensorAnnouncement.of(scanning, Scanning(emptyList(), 9_000L)))
        assertNull(AddSensorAnnouncement.of(Results(found, false, 2), Results(found, false, 1)))
        assertNull(AddSensorAnnouncement.of(null, Results(emptyList(), true, 0)))
    }
}
