package com.runner.academy.sensor

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FakeHeartRateSensorTest {

    @Test
    fun `leases are counted until released`() {
        val sensor = FakeHeartRateSensor()
        assertEquals(0, sensor.leaseCount)
        val workout = sensor.acquire("workout")
        val devices = sensor.acquire("devices")
        assertEquals(2, sensor.leaseCount)
        assertEquals(listOf("workout", "devices"), sensor.owners)
        workout.release()
        assertEquals(1, sensor.leaseCount)
        assertEquals(listOf("devices"), sensor.owners)
        devices.release()
        assertEquals(0, sensor.leaseCount)
    }

    @Test
    fun `releasing twice counts once`() {
        val sensor = FakeHeartRateSensor()
        val first = sensor.acquire("a")
        sensor.acquire("b")
        first.release()
        first.release()
        assertEquals(1, sensor.leaseCount)
    }

    @Test
    fun `set link updates the status`() {
        val sensor = FakeHeartRateSensor()
        assertEquals(SensorLink.IDLE, sensor.status.value.link)
        sensor.setLink(SensorLink.CONNECTED, batteryPercent = 80)
        assertEquals(SensorStatus(SensorLink.CONNECTED, 80, null), sensor.status.value)
    }

    @Test
    fun `emitted samples reach collectors`() = runTest(UnconfinedTestDispatcher()) {
        val sensor = FakeHeartRateSensor()
        val got = mutableListOf<HeartRateSample>()
        val job = launch { sensor.samples.toList(got) }
        sensor.emit(142, atNanos = 1_000L)
        sensor.emit(null, atNanos = 2_000L)
        job.cancel()
        assertEquals(listOf(HeartRateSample(1_000L, 142), HeartRateSample(2_000L, null)), got)
    }
}
