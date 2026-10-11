package com.runner.academy.sensor

import com.runner.academy.sensor.LinkCommand.Close
import com.runner.academy.sensor.LinkCommand.ConnectDirect
import com.runner.academy.sensor.LinkCommand.Discover
import com.runner.academy.sensor.LinkCommand.EnableHrNotify
import com.runner.academy.sensor.LinkCommand.ReadBattery
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorProbeTest {

    private val addr = "AA:BB:CC:DD:EE:FF"
    private val scheduler = FakeLinkScheduler()
    private val link = FakeGattLink().apply { deviceName = "HR Strap 10 1234" }
    private val results = mutableListOf<ProbeResult>()
    private val probe = SensorProbe(link, scheduler, nowMs = { scheduler.nowMs }, onResult = { results += it })

    private fun throughDiscovery() {
        probe.start(addr)
        link.event(LinkEvent.Connected)
        link.event(LinkEvent.ServicesOk(hasHrs = true))
    }

    @Test
    fun `connects directly, discovers and reads the battery`() {
        throughDiscovery()
        val commands = link.drain()
        assertEquals(ConnectDirect(addr), commands.first { it is ConnectDirect })
        assertEquals(listOf(Discover, ReadBattery), commands.filter { it == Discover || it == ReadBattery })
        assertEquals(emptyList<ProbeResult>(), results)

        link.listener!!.onBattery(80)
        assertEquals(listOf(ProbeResult.Ok("HR Strap 10 1234", 80)), results)
    }

    @Test
    fun `notifications are not needed to pass`() {
        throughDiscovery()
        assertFalse(EnableHrNotify in link.drain())
        // A device refusing CCCD writes is still a heart-rate sensor: nothing waits for one
        link.listener!!.onBattery(null)
        assertEquals(listOf(ProbeResult.Ok("HR Strap 10 1234", null)), results)
    }

    @Test
    fun `the result closes the connection`() {
        throughDiscovery()
        link.listener!!.onBattery(null)
        assertTrue(link.stopped)
        assertEquals(0, scheduler.pendingCount)
    }

    @Test
    fun `a device without HRS is not a heart-rate sensor`() {
        probe.start(addr)
        link.event(LinkEvent.Connected)
        link.event(LinkEvent.ServicesOk(hasHrs = false))
        assertEquals(listOf(ProbeResult.Failed(ProbeFailure.NOT_HEART_RATE_SENSOR)), results)
        assertTrue(link.stopped)
    }

    @Test
    fun `a 133 is retried like any connection`() {
        probe.start(addr)
        link.drain()
        link.event(LinkEvent.Disconnected(133))
        link.event(LinkEvent.Timeout(LinkTimer.RETRY))
        assertTrue(link.drain().any { it is ConnectDirect })
        assertEquals(emptyList<ProbeResult>(), results)
    }

    @Test
    fun `a drop during the battery read reconnects and reads again`() {
        throughDiscovery()
        link.event(LinkEvent.Disconnected(8))
        link.event(LinkEvent.Timeout(LinkTimer.RETRY))
        link.drain()
        link.event(LinkEvent.Connected)
        link.event(LinkEvent.ServicesOk(hasHrs = true))
        assertTrue(ReadBattery in link.drain())
        link.listener!!.onBattery(55)
        assertEquals(listOf(ProbeResult.Ok("HR Strap 10 1234", 55)), results)
    }

    @Test
    fun `no answer within the probe time is unreachable`() {
        probe.start(addr)
        scheduler.advanceBy(SensorProbe.PROBE_TIMEOUT_MS - 1)
        assertEquals(emptyList<ProbeResult>(), results)
        scheduler.advanceBy(1)
        assertEquals(listOf(ProbeResult.Failed(ProbeFailure.UNREACHABLE)), results)
        assertTrue(link.stopped)
    }

    @Test
    fun `Bluetooth turned off ends the probe`() {
        probe.start(addr)
        link.event(LinkEvent.BtOff)
        assertEquals(listOf(ProbeResult.Failed(ProbeFailure.BLUETOOTH_OFF)), results)
    }

    @Test
    fun `cancel closes without a result`() {
        probe.start(addr)
        probe.cancel()
        assertTrue(link.stopped)
        assertTrue(Close in link.commands)
        scheduler.advanceBy(SensorProbe.PROBE_TIMEOUT_MS)
        assertEquals(emptyList<ProbeResult>(), results)
    }

    @Test
    fun `only one result`() {
        throughDiscovery()
        val listener = link.listener!!
        listener.onBattery(80)
        listener.onBattery(70)
        listener.onEvent(LinkEvent.BtOff)
        assertEquals(1, results.size)
        assertFalse(results.single() is ProbeResult.Failed)
    }

    @Test
    fun `run holds the sensor off the device until the result`() = runBlocking {
        val held = mutableListOf<String>()
        var released = 0
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            SensorProbe.run(addr, link, scheduler, { 0L }, holdOff = { held += it; SensorLease { released++ } })
        }
        assertEquals(listOf(addr), held)
        assertEquals(0, released)

        link.event(LinkEvent.Connected)
        link.event(LinkEvent.ServicesOk(hasHrs = true))
        link.listener!!.onBattery(80)
        assertEquals(ProbeResult.Ok("HR Strap 10 1234", 80), result.await())
        assertEquals(1, released)
    }

    @Test
    fun `a cancelled run disconnects and gives the device back`() = runBlocking {
        var released = 0
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            SensorProbe.run(addr, link, scheduler, { 0L }, holdOff = { SensorLease { released++ } })
        }
        result.cancel()
        result.join()
        assertTrue(link.stopped)
        assertEquals(1, released)
    }
}
