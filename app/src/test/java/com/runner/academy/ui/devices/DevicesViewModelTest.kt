package com.runner.academy.ui.devices

import com.runner.academy.R
import com.runner.academy.sensor.FakeHeartRateSensor
import com.runner.academy.sensor.HeartRateSensor
import com.runner.academy.sensor.PairedSensor
import com.runner.academy.sensor.SensorLease
import com.runner.academy.sensor.SensorLink
import com.runner.academy.sensor.SensorType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
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
class DevicesViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val strap = PairedSensor(SensorType.HEART_RATE, "AA:BB", "HR Strap 10", 1L, 7_000L, 80)
    private val paired = MutableStateFlow<Map<SensorType, PairedSensor>>(mapOf(SensorType.HEART_RATE to strap))
    private val sensor = FakeHeartRateSensor(SensorLink.CONNECTING)
    private val forgotten = mutableListOf<SensorType>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.viewModel() = DevicesViewModel(
        sensor = sensor,
        paired = paired,
        forgetSensor = { forgotten += it; paired.value = paired.value - it },
        nowMs = { testScheduler.currentTime }
    )

    @Test
    fun `takes the lease while started and gives it back on stop`() = runTest(dispatcher) {
        val vm = viewModel()
        assertEquals(0, sensor.leaseCount)

        vm.onStart()
        vm.onStart()
        assertEquals(1, sensor.leaseCount)
        assertEquals(listOf(DevicesViewModel.LEASE_OWNER), sensor.owners)

        vm.onStop()
        assertEquals(0, sensor.leaseCount)
        vm.onStop()
        assertEquals(0, sensor.leaseCount)
    }

    @Test
    fun `no paired device is the empty state`() = runTest(dispatcher) {
        paired.value = emptyMap()
        val vm = viewModel()
        vm.onStart()
        runCurrent()

        assertNull(vm.state.value.heartRate)
        vm.onStop()
    }

    @Test
    fun `bluetooth off offers to turn it on`() = runTest(dispatcher) {
        sensor.setLink(SensorLink.BLUETOOTH_OFF)
        val vm = viewModel()
        vm.onStart()
        runCurrent()

        val row = vm.state.value.heartRate!!
        assertEquals(R.string.devices_status_bluetooth_off, row.status)
        assertEquals(DeviceAction.ENABLE_BLUETOOTH, row.action)
        vm.onStop()
    }

    @Test
    fun `searching turns offline after 30 s, with the last contact`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onStart()
        runCurrent()
        sensor.setLink(SensorLink.SEARCHING, lastSeenAt = 9_000L)
        runCurrent()
        assertEquals(R.string.devices_status_searching, vm.state.value.heartRate!!.status)

        advanceTimeBy(29_000L)
        runCurrent()
        assertEquals(R.string.devices_status_searching, vm.state.value.heartRate!!.status)

        advanceTimeBy(1_500L)
        runCurrent()
        val row = vm.state.value.heartRate!!
        assertEquals(R.string.devices_status_offline, row.status)
        assertEquals(9_000L, row.lastSeenAt)
        vm.onStop()
    }

    @Test
    fun `the pulse shows at most once a second, the newest one`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onStart()
        sensor.setLink(SensorLink.CONNECTED)
        runCurrent()
        assertNull(vm.state.value.heartRate!!.bpm)

        sensor.emit(140, 1L)
        sensor.emit(142, 2L)
        runCurrent()
        assertNull(vm.state.value.heartRate!!.bpm)

        advanceTimeBy(1_001L)
        runCurrent()
        assertEquals(142, vm.state.value.heartRate!!.bpm)
        vm.onStop()
    }

    @Test
    fun `losing the connection drops the pulse`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onStart()
        sensor.setLink(SensorLink.CONNECTED)
        runCurrent()
        sensor.emit(150, 1L)
        advanceTimeBy(1_001L)
        runCurrent()
        assertEquals(150, vm.state.value.heartRate!!.bpm)

        sensor.setLink(SensorLink.CONNECTING)
        runCurrent()
        sensor.setLink(SensorLink.CONNECTED)
        runCurrent()
        advanceTimeBy(1_001L)
        runCurrent()

        val row = vm.state.value.heartRate!!
        assertEquals(R.string.devices_status_connected, row.status)
        assertNull(row.bpm)
        vm.onStop()
    }

    @Test
    fun `a granted permission takes a new lease before giving back the old one`() = runTest(dispatcher) {
        var acquired = 0
        val counting = object : HeartRateSensor by sensor {
            override fun acquire(owner: String): SensorLease {
                acquired++
                return sensor.acquire(owner)
            }
        }
        val vm = DevicesViewModel(counting, paired, { }, { testScheduler.currentTime })
        vm.onStart()
        sensor.setLink(SensorLink.NO_PERMISSION)
        runCurrent()

        vm.onPermissionResult(granted = true, rationale = false)
        sensor.setLink(SensorLink.CONNECTING)
        runCurrent()

        assertEquals(2, acquired)
        assertEquals(1, sensor.leaseCount)
        assertEquals(R.string.devices_status_searching, vm.state.value.heartRate!!.status)

        vm.onStop()
        assertEquals(0, sensor.leaseCount)
    }

    @Test
    fun `a granted permission while stopped takes no lease`() = runTest(dispatcher) {
        val vm = viewModel()

        vm.onPermissionGranted()

        assertEquals(0, sensor.leaseCount)
    }

    @Test
    fun `a dismissed first dialog keeps Allow, a refusal for good switches to settings`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onStart()
        runCurrent()

        vm.onPermissionRequest(rationale = false)
        vm.onPermissionResult(granted = false, rationale = false)
        runCurrent()
        assertFalse(vm.state.value.permissionDeniedForGood)

        vm.onPermissionRequest(rationale = false)
        vm.onPermissionResult(granted = false, rationale = true)
        runCurrent()
        assertFalse(vm.state.value.permissionDeniedForGood)

        vm.onPermissionRequest(rationale = true)
        vm.onPermissionResult(granted = false, rationale = false)
        runCurrent()
        assertTrue(vm.state.value.permissionDeniedForGood)
        vm.onStop()
    }

    @Test
    fun `forget removes the sensor`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onStart()
        runCurrent()

        vm.forget(SensorType.HEART_RATE)
        runCurrent()

        assertEquals(listOf(SensorType.HEART_RATE), forgotten)
        assertNull(vm.state.value.heartRate)
        vm.onStop()
    }
}
