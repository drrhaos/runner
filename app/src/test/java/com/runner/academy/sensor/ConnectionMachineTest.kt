package com.runner.academy.sensor

import com.runner.academy.sensor.LinkCommand.Cancel
import com.runner.academy.sensor.LinkCommand.Close
import com.runner.academy.sensor.LinkCommand.ConnectAuto
import com.runner.academy.sensor.LinkCommand.ConnectDirect
import com.runner.academy.sensor.LinkCommand.Discover
import com.runner.academy.sensor.LinkCommand.EnableHrNotify
import com.runner.academy.sensor.LinkCommand.ReadBattery
import com.runner.academy.sensor.LinkCommand.Schedule
import com.runner.academy.sensor.LinkEvent.Acquired
import com.runner.academy.sensor.LinkEvent.AddressChanged
import com.runner.academy.sensor.LinkEvent.BtOff
import com.runner.academy.sensor.LinkEvent.BtOn
import com.runner.academy.sensor.LinkEvent.Connected
import com.runner.academy.sensor.LinkEvent.Disconnected
import com.runner.academy.sensor.LinkEvent.NotifyEnabled
import com.runner.academy.sensor.LinkEvent.OpFailed
import com.runner.academy.sensor.LinkEvent.Released
import com.runner.academy.sensor.LinkEvent.Sample
import com.runner.academy.sensor.LinkEvent.ServicesOk
import com.runner.academy.sensor.LinkEvent.Timeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionMachineTest {

    private val addr = "AA:BB:CC:DD:EE:FF"

    /** Drives a machine with a fake clock and keeps every command it ever returned. */
    private inner class Run(address: String? = addr, bluetoothOn: Boolean = true) {
        val machine = ConnectionMachine(address, bluetoothOn)
        val all = mutableListOf<LinkCommand>()
        var now = 1_000_000L

        fun on(event: LinkEvent, afterMs: Long = 0L): List<LinkCommand> {
            now += afterMs
            return machine.on(event, now).also { all += it }
        }

        /** Acquired, connected and notifying: the steady state. */
        fun connected(): Run {
            on(Acquired)
            on(Connected, 500)
            on(ServicesOk(hasHrs = true), 100)
            on(NotifyEnabled, 100)
            return this
        }

        /** A direct attempt after the [retryMs] pause fails with 133. */
        fun failRetry(retryMs: Long): List<LinkCommand> {
            on(Timeout(LinkTimer.RETRY), retryMs)
            return on(Disconnected(GATT_ERROR), 2_000)
        }
    }

    // --- First connection -------------------------------------------------------------------

    @Test
    fun `a fresh machine is idle`() {
        assertEquals(SensorLink.IDLE, ConnectionMachine(addr).link)
        assertEquals(SensorLink.BLUETOOTH_OFF, ConnectionMachine(addr, bluetoothOn = false).link)
    }

    @Test
    fun `acquired connects directly with a 30 s timeout`() {
        val run = Run()
        assertEquals(
            listOf(Close, ConnectDirect(addr), Schedule(LinkTimer.CONNECT, 30_000L)),
            run.on(Acquired)
        )
        assertEquals(SensorLink.CONNECTING, run.machine.link)
    }

    @Test
    fun `acquired without a paired device does nothing`() {
        val run = Run(address = null)
        assertEquals(emptyList<LinkCommand>(), run.on(Acquired))
        assertEquals(SensorLink.IDLE, run.machine.link)
    }

    @Test
    fun `acquired with Bluetooth off waits for it`() {
        val run = Run(bluetoothOn = false)
        assertEquals(emptyList<LinkCommand>(), run.on(Acquired))
        assertEquals(SensorLink.BLUETOOTH_OFF, run.machine.link)
        assertEquals(
            listOf(Close, ConnectDirect(addr), Schedule(LinkTimer.CONNECT, 30_000L)),
            run.on(BtOn)
        )
    }

    @Test
    fun `the first connect timing out goes to the retry pause`() {
        val run = Run()
        run.on(Acquired)
        assertEquals(
            listOf(Close, Schedule(LinkTimer.RETRY, 1_000L)),
            run.on(Timeout(LinkTimer.CONNECT), 30_000)
        )
        assertEquals(SensorLink.CONNECTING, run.machine.link)
    }

    // --- Case 4: setup after every connection -----------------------------------------------

    @Test
    fun `connected is followed by discover, notify and battery`() {
        val run = Run()
        run.on(Acquired)
        assertEquals(listOf(Cancel(LinkTimer.CONNECT), Discover, Schedule(LinkTimer.SILENCE, 15_000L)), run.on(Connected))
        assertEquals(SensorLink.CONNECTED, run.machine.link)
        assertEquals(listOf(EnableHrNotify), run.on(ServicesOk(hasHrs = true)))
        assertEquals(listOf(ReadBattery), run.on(NotifyEnabled))
        assertEquals(SensorLink.CONNECTED, run.machine.link)
    }

    @Test
    fun `every reconnection repeats discover and notify`() {
        val run = Run().connected()
        run.on(Disconnected(8), 10_000)
        run.on(Timeout(LinkTimer.RETRY), 1_000)
        val setup = listOf(run.on(Connected, 500), run.on(ServicesOk(true)), run.on(NotifyEnabled)).flatten()
        assertEquals(listOf(Discover, EnableHrNotify, ReadBattery), setup.filter { it !is Schedule && it !is Cancel })
    }

    @Test
    fun `15 s of silence while connected reconnects`() {
        val run = Run().connected()
        assertEquals(emptyList<LinkCommand>(), run.on(Sample, 1_000))
        val out = run.on(Timeout(LinkTimer.SILENCE), 15_000)
        assertEquals(Close, out.first())
        assertTrue(out.contains(Schedule(LinkTimer.RETRY, 1_000L)))
        assertEquals(SensorLink.CONNECTING, run.machine.link)
        assertEquals(listOf(Close, ConnectDirect(addr), Schedule(LinkTimer.CONNECT, 30_000L)), run.on(Timeout(LinkTimer.RETRY), 1_000))
    }

    @Test
    fun `samples keep the silence timer from firing`() {
        val run = Run().connected()
        // The timer was set at Connected; samples arrived since, so it is re-armed for the rest
        run.on(Sample, 9_000)
        assertEquals(listOf(Schedule(LinkTimer.SILENCE, 9_300L)), run.on(Timeout(LinkTimer.SILENCE), 5_700))
        assertEquals(SensorLink.CONNECTED, run.machine.link)
        run.on(Sample, 9_000)
        assertEquals(listOf(Schedule(LinkTimer.SILENCE, 5_700L)), run.on(Timeout(LinkTimer.SILENCE), 9_300))
    }

    @Test
    fun `a setup that never notifies counts as silence`() {
        val run = Run()
        run.on(Acquired)
        run.on(Connected, 500)
        run.on(ServicesOk(true))
        assertEquals(Close, run.on(Timeout(LinkTimer.SILENCE), 15_000).first())
        assertEquals(SensorLink.CONNECTING, run.machine.link)
    }

    // --- Case 2: errors and backoff ---------------------------------------------------------

    @Test
    fun `a 133 drop closes, pauses 1 s, then connects directly`() {
        val run = Run().connected()
        assertEquals(
            listOf(Cancel(LinkTimer.SILENCE), Close, Schedule(LinkTimer.RETRY, 1_000L)),
            run.on(Disconnected(GATT_ERROR), 60_000)
        )
        assertEquals(
            listOf(Close, ConnectDirect(addr), Schedule(LinkTimer.CONNECT, 30_000L)),
            run.on(Timeout(LinkTimer.RETRY), 1_000)
        )
    }

    @Test
    fun `pauses are 1, 3 and 7 s, then one pending auto connect`() {
        val run = Run().connected()
        run.on(Disconnected(GATT_ERROR), 60_000)
        assertEquals(listOf(Close, Schedule(LinkTimer.RETRY, 3_000L)), run.failRetry(1_000).filter { it !is Cancel })
        assertEquals(listOf(Close, Schedule(LinkTimer.RETRY, 7_000L)), run.failRetry(3_000).filter { it !is Cancel })
        val last = run.failRetry(7_000).filter { it !is Cancel }
        assertEquals(listOf(Close, ConnectAuto(addr)), last)
        assertEquals(SensorLink.SEARCHING, run.machine.link)
    }

    @Test
    fun `the auto connect has no timeout`() {
        val run = Run().connected()
        run.on(Disconnected(GATT_ERROR), 60_000)
        run.failRetry(1_000)
        run.failRetry(3_000)
        run.failRetry(7_000)
        assertFalse(run.all.takeLastWhile { it != ConnectAuto(addr) }.any { it is Schedule })
        assertEquals(emptyList<LinkCommand>(), run.on(Timeout(LinkTimer.CONNECT), 30_000))
        assertEquals(SensorLink.SEARCHING, run.machine.link)
    }

    @Test
    fun `a direct attempt that times out counts as a failure`() {
        val run = Run().connected()
        run.on(Disconnected(GATT_ERROR), 60_000)
        run.on(Timeout(LinkTimer.RETRY), 1_000)
        assertEquals(listOf(Close, Schedule(LinkTimer.RETRY, 3_000L)), run.on(Timeout(LinkTimer.CONNECT), 30_000))
    }

    @Test
    fun `the auto connect succeeding sets up and starts the retry count anew`() {
        val run = Run().connected()
        run.on(Disconnected(GATT_ERROR), 60_000)
        run.failRetry(1_000)
        run.failRetry(3_000)
        run.failRetry(7_000)
        assertEquals(listOf(Discover, Schedule(LinkTimer.SILENCE, 15_000L)), run.on(Connected, 120_000))
        assertEquals(SensorLink.CONNECTED, run.machine.link)
        run.on(ServicesOk(true))
        run.on(NotifyEnabled)
        assertTrue(run.on(Disconnected(8), 30_000).contains(Schedule(LinkTimer.RETRY, 1_000L)))
    }

    @Test
    fun `a failed auto connect is retried after the longest pause`() {
        val run = Run().connected()
        run.on(Disconnected(GATT_ERROR), 60_000)
        run.failRetry(1_000)
        run.failRetry(3_000)
        run.failRetry(7_000)
        assertEquals(listOf(Close, Schedule(LinkTimer.RETRY, 7_000L)), run.on(Disconnected(GATT_ERROR), 60_000))
        assertEquals(listOf(Close, ConnectAuto(addr)), run.on(Timeout(LinkTimer.RETRY), 7_000))
    }

    @Test
    fun `a setup step failing reconnects, a failed battery read does not`() {
        val run = Run()
        run.on(Acquired)
        run.on(Connected)
        assertEquals(Close, run.on(OpFailed).filter { it !is Cancel }.first())
        assertEquals(SensorLink.CONNECTING, run.machine.link)

        val ok = Run().connected()
        assertEquals(emptyList<LinkCommand>(), ok.on(OpFailed))
        assertEquals(SensorLink.CONNECTED, ok.machine.link)
    }

    @Test
    fun `a device without the heart rate service is given up`() {
        val run = Run()
        run.on(Acquired)
        run.on(Connected)
        assertEquals(listOf(Cancel(LinkTimer.SILENCE), Close), run.on(ServicesOk(hasHrs = false)))
        assertEquals(SensorLink.IDLE, run.machine.link)
        assertEquals(LinkFailure.NO_HEART_RATE_SERVICE, run.machine.failure)
        assertEquals(emptyList<LinkCommand>(), run.on(Disconnected(0), 5_000))
        assertEquals(emptyList<LinkCommand>(), run.on(Timeout(LinkTimer.RETRY), 5_000))
    }

    @Test
    fun `a new address clears the failure and connects again`() {
        val run = Run()
        run.on(Acquired)
        run.on(Connected)
        run.on(ServicesOk(hasHrs = false))
        val other = "11:22:33:44:55:66"
        assertEquals(listOf(Close, ConnectDirect(other), Schedule(LinkTimer.CONNECT, 30_000L)), run.on(AddressChanged(other)))
        assertNull(run.machine.failure)
        assertEquals(SensorLink.CONNECTING, run.machine.link)
    }

    // --- Case 3: Bluetooth ------------------------------------------------------------------

    @Test
    fun `bluetooth off while connected closes and waits for it`() {
        val run = Run().connected()
        assertEquals(listOf(Cancel(LinkTimer.SILENCE), Close), run.on(BtOff, 30_000))
        assertEquals(SensorLink.BLUETOOTH_OFF, run.machine.link)

        val waiting = listOf(
            run.on(Disconnected(8), 100),
            run.on(Timeout(LinkTimer.SILENCE), 15_000),
            run.on(Timeout(LinkTimer.RETRY), 1_000),
            run.on(Sample),
            run.on(Released),
            run.on(Acquired),
            run.on(AddressChanged("11:22:33:44:55:66"))
        ).flatten()
        assertFalse(waiting.any { it is ConnectDirect || it is ConnectAuto })
        assertEquals(SensorLink.BLUETOOTH_OFF, run.machine.link)

        assertEquals(
            listOf(Close, ConnectDirect("11:22:33:44:55:66"), Schedule(LinkTimer.CONNECT, 30_000L)),
            run.on(BtOn, 60_000)
        )
    }

    @Test
    fun `bluetooth off during the retry pause cancels the pause`() {
        val run = Run().connected()
        run.on(Disconnected(GATT_ERROR))
        assertEquals(listOf(Cancel(LinkTimer.RETRY), Close), run.on(BtOff))
    }

    @Test
    fun `bluetooth on without a lease stays idle`() {
        val run = Run(bluetoothOn = false)
        assertEquals(emptyList<LinkCommand>(), run.on(BtOn))
        assertEquals(SensorLink.IDLE, run.machine.link)
    }

    @Test
    fun `bluetooth on restarts the retry count`() {
        val run = Run().connected()
        run.on(Disconnected(GATT_ERROR))
        run.failRetry(1_000)
        run.failRetry(3_000)
        run.on(BtOff)
        run.on(BtOn)
        assertEquals(listOf(Close, Schedule(LinkTimer.RETRY, 1_000L)), run.on(Timeout(LinkTimer.CONNECT), 30_000))
    }

    // --- Leases and address -----------------------------------------------------------------

    @Test
    fun `released closes and goes idle`() {
        val run = Run().connected()
        assertEquals(listOf(Cancel(LinkTimer.SILENCE), Close), run.on(Released))
        assertEquals(SensorLink.IDLE, run.machine.link)
        assertEquals(emptyList<LinkCommand>(), run.on(Timeout(LinkTimer.SILENCE), 15_000))
        assertEquals(emptyList<LinkCommand>(), run.on(Connected))
    }

    @Test
    fun `released while searching closes the pending auto connect`() {
        val run = Run().connected()
        run.on(Disconnected(GATT_ERROR))
        run.failRetry(1_000)
        run.failRetry(3_000)
        run.failRetry(7_000)
        assertEquals(listOf(Close), run.on(Released))
        assertEquals(SensorLink.IDLE, run.machine.link)
    }

    @Test
    fun `released while idle is harmless`() {
        val run = Run()
        assertEquals(listOf(Close), run.on(Released))
        assertEquals(SensorLink.IDLE, run.machine.link)
    }

    @Test
    fun `a second acquired changes nothing`() {
        val run = Run().connected()
        assertEquals(emptyList<LinkCommand>(), run.on(Acquired))
        assertEquals(SensorLink.CONNECTED, run.machine.link)
    }

    @Test
    fun `address changed while connected reconnects to the new address`() {
        val run = Run().connected()
        val other = "11:22:33:44:55:66"
        assertEquals(
            listOf(Cancel(LinkTimer.SILENCE), Close, ConnectDirect(other), Schedule(LinkTimer.CONNECT, 30_000L)),
            run.on(AddressChanged(other))
        )
        assertEquals(other, run.machine.address)
    }

    @Test
    fun `the same address again changes nothing`() {
        val run = Run().connected()
        assertEquals(emptyList<LinkCommand>(), run.on(AddressChanged(addr)))
    }

    @Test
    fun `unpairing while connected closes and goes idle`() {
        val run = Run().connected()
        assertEquals(listOf(Cancel(LinkTimer.SILENCE), Close), run.on(AddressChanged(null)))
        assertEquals(SensorLink.IDLE, run.machine.link)
    }

    @Test
    fun `pairing while held connects`() {
        val run = Run(address = null)
        run.on(Acquired)
        assertEquals(listOf(Close, ConnectDirect(addr), Schedule(LinkTimer.CONNECT, 30_000L)), run.on(AddressChanged(addr)))
    }

    @Test
    fun `address changed without a lease only remembers it`() {
        val run = Run()
        assertEquals(emptyList<LinkCommand>(), run.on(AddressChanged("11:22:33:44:55:66")))
        assertEquals(listOf(Close, ConnectDirect("11:22:33:44:55:66"), Schedule(LinkTimer.CONNECT, 30_000L)), run.on(Acquired))
    }

    @Test
    fun `stale callbacks out of place are ignored`() {
        val run = Run()
        run.on(Acquired)
        assertEquals(emptyList<LinkCommand>(), run.on(NotifyEnabled))
        assertEquals(emptyList<LinkCommand>(), run.on(ServicesOk(true)))
        assertEquals(emptyList<LinkCommand>(), run.on(Timeout(LinkTimer.RETRY)))
        assertEquals(emptyList<LinkCommand>(), run.on(Timeout(LinkTimer.SILENCE)))
        assertEquals(SensorLink.CONNECTING, run.machine.link)
    }

    // --- Invariant: one GATT object at a time -----------------------------------------------

    @Test
    fun `every connect is directly preceded by close, in every scenario`() {
        val scenarios: List<Run.() -> Unit> = listOf(
            {
                connected()
                on(Disconnected(GATT_ERROR), 60_000)
                failRetry(1_000); failRetry(3_000); failRetry(7_000)
                on(Connected, 90_000); on(ServicesOk(true)); on(NotifyEnabled)
                on(Timeout(LinkTimer.SILENCE), 15_000)
                on(Timeout(LinkTimer.RETRY), 1_000)
            },
            {
                connected()
                on(BtOff, 5_000); on(BtOn, 5_000)
                on(Timeout(LinkTimer.CONNECT), 30_000)
                on(Timeout(LinkTimer.RETRY), 1_000)
                on(AddressChanged("11:22:33:44:55:66"))
                on(Released); on(Acquired)
            },
            {
                on(Acquired)
                on(Timeout(LinkTimer.CONNECT), 30_000)
                on(Timeout(LinkTimer.RETRY), 1_000)
                on(Connected); on(OpFailed)
                on(Timeout(LinkTimer.RETRY), 3_000)
                on(Connected); on(ServicesOk(false))
                on(AddressChanged("11:22:33:44:55:66"))
                on(Disconnected(GATT_ERROR))
                on(Timeout(LinkTimer.RETRY), 1_000)
            },
            {
                on(BtOff); on(Acquired); on(BtOn)
                on(Released); on(BtOff); on(BtOn); on(Acquired)
                on(AddressChanged(null)); on(AddressChanged(addr))
            }
        )
        scenarios.forEachIndexed { index, scenario ->
            val run = Run().apply(scenario)
            val connects = run.all.indices.filter { run.all[it] is ConnectDirect || run.all[it] is ConnectAuto }
            assertTrue("scenario $index made no connect", connects.isNotEmpty())
            connects.forEach { i ->
                assertTrue("scenario $index: ${run.all[i]} at $i without Close: ${run.all}", i > 0 && run.all[i - 1] == Close)
            }
        }
    }

    private companion object {
        const val GATT_ERROR = 133
    }
}
