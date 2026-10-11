package com.runner.academy.sensor

import android.bluetooth.BluetoothGatt.GATT_SUCCESS
import com.runner.academy.sensor.GattCharacteristic.Companion.BATTERY_LEVEL
import com.runner.academy.sensor.GattCharacteristic.Companion.HEART_RATE_MEASUREMENT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BleGattLinkTest {

    private val addr = "AA:BB:CC:DD:EE:FF"
    private val scheduler = FakeLinkScheduler()
    private val connector = FakeGattConnector()
    private val adapter = FakeAdapterWatch()
    private var nanos = 7_000L
    private val link = BleGattLink(connector, adapter, scheduler, elapsedNanos = { nanos })

    private val events = mutableListOf<LinkEvent>()
    private val heartRates = mutableListOf<Pair<List<Byte>, Long>>()
    private val batteries = mutableListOf<Int?>()
    private val errors = mutableListOf<Int?>()

    private val listener = object : GattLink.Listener {
        override fun onEvent(event: LinkEvent) {
            events += event
        }

        override fun onHeartRate(value: ByteArray, elapsedNanos: Long) {
            heartRates += value.toList() to elapsedNanos
        }

        override fun onBattery(percent: Int?) {
            batteries += percent
        }

        override fun onGattError(status: Int?) {
            errors += status
        }
    }

    private val callback: GattCallback get() = connector.last.callback
    private val handle: FakeGattHandle get() = connector.last.handle!!

    @Before
    fun setUp() {
        link.start(listener)
    }

    /** Connected, discovered, notifications on: what the machine does after a connect. */
    private fun setUpConnection() {
        link.execute(LinkCommand.ConnectDirect(addr))
        callback.onConnectionStateChange(GATT_SUCCESS, connected = true)
        link.execute(LinkCommand.Discover)
        callback.onServicesDiscovered(GATT_SUCCESS)
        link.execute(LinkCommand.EnableHrNotify)
        callback.onDescriptorWrite(HEART_RATE_MEASUREMENT, GATT_SUCCESS)
        events.clear()
    }

    @Test
    fun `connect commands open an LE connection, direct or auto`() {
        link.execute(LinkCommand.ConnectDirect(addr))
        assertEquals(addr, connector.last.address)
        assertFalse(connector.last.autoConnect)

        link.execute(LinkCommand.Close)
        link.execute(LinkCommand.ConnectAuto(addr))
        assertTrue(connector.last.autoConnect)
        assertTrue(connector.attempts[0].handle!!.closed)
    }

    @Test
    fun `close closes the GATT object`() {
        link.execute(LinkCommand.ConnectDirect(addr))
        link.execute(LinkCommand.Close)
        assertEquals(listOf("close"), handle.calls)
        // Nothing to close any more
        link.execute(LinkCommand.Close)
        assertEquals(listOf("close"), handle.calls)
    }

    @Test
    fun `connection state callbacks become events`() {
        link.execute(LinkCommand.ConnectDirect(addr))
        callback.onConnectionStateChange(GATT_SUCCESS, connected = true)
        callback.onConnectionStateChange(133, connected = false)
        assertEquals(listOf(LinkEvent.Connected, LinkEvent.Disconnected(133)), events)
    }

    @Test
    fun `a connected state with an error status is a failure`() {
        link.execute(LinkCommand.ConnectDirect(addr))
        callback.onConnectionStateChange(133, connected = true)
        assertEquals(listOf(LinkEvent.Disconnected(133)), events)
    }

    @Test
    fun `a connect that does not start is a disconnect`() {
        connector.accepts = false
        link.execute(LinkCommand.ConnectDirect(addr))
        assertEquals(listOf(LinkEvent.Disconnected(BleGattLink.GATT_FAILURE)), events)
    }

    @Test
    fun `callbacks of a closed connection are ignored`() {
        link.execute(LinkCommand.ConnectDirect(addr))
        val old = callback
        link.execute(LinkCommand.Close)
        link.execute(LinkCommand.ConnectDirect(addr))
        old.onConnectionStateChange(GATT_SUCCESS, connected = true)
        old.onConnectionStateChange(8, connected = false)
        old.onCharacteristicChanged(HEART_RATE_MEASUREMENT, byteArrayOf(0, 60))
        assertEquals(emptyList<LinkEvent>(), events)
        assertEquals(emptyList<Any>(), heartRates)
    }

    @Test
    fun `discover reports whether the heart rate service is there`() {
        link.execute(LinkCommand.ConnectDirect(addr))
        callback.onConnectionStateChange(GATT_SUCCESS, connected = true)
        link.execute(LinkCommand.Discover)
        assertEquals(listOf("discover"), handle.calls)
        callback.onServicesDiscovered(GATT_SUCCESS)
        assertEquals(LinkEvent.ServicesOk(hasHrs = true), events.last())
    }

    @Test
    fun `a device without heart rate measurement has no HRS`() {
        connector.characteristics = emptyMap()
        link.execute(LinkCommand.ConnectDirect(addr))
        link.execute(LinkCommand.Discover)
        callback.onServicesDiscovered(GATT_SUCCESS)
        assertEquals(listOf(LinkEvent.ServicesOk(hasHrs = false)), events)
    }

    @Test
    fun `a failed discovery is a failed operation`() {
        link.execute(LinkCommand.ConnectDirect(addr))
        link.execute(LinkCommand.Discover)
        callback.onServicesDiscovered(129)
        assertEquals(listOf(LinkEvent.OpFailed), events)
        assertEquals(listOf<Int?>(129), errors)
    }

    @Test
    fun `enable notify writes the CCCD of heart rate measurement`() {
        link.execute(LinkCommand.ConnectDirect(addr))
        link.execute(LinkCommand.Discover)
        callback.onServicesDiscovered(GATT_SUCCESS)
        link.execute(LinkCommand.EnableHrNotify)
        assertEquals(listOf("discover", "notify:2a37"), handle.calls)
        callback.onDescriptorWrite(HEART_RATE_MEASUREMENT, GATT_SUCCESS)
        assertEquals(LinkEvent.NotifyEnabled, events.last())
    }

    @Test
    fun `operations queued together run one after another`() {
        link.execute(LinkCommand.ConnectDirect(addr))
        link.execute(LinkCommand.Discover)
        link.execute(LinkCommand.EnableHrNotify)
        assertEquals(listOf("discover"), handle.calls)
        callback.onServicesDiscovered(GATT_SUCCESS)
        assertEquals(listOf("discover", "notify:2a37"), handle.calls)
    }

    @Test
    fun `an operation without a callback for 5 s fails`() {
        link.execute(LinkCommand.ConnectDirect(addr))
        callback.onConnectionStateChange(GATT_SUCCESS, connected = true)
        link.execute(LinkCommand.Discover)
        scheduler.advanceBy(GattOpQueue.OP_TIMEOUT_MS)
        assertEquals(listOf(LinkEvent.Connected, LinkEvent.OpFailed), events)
        assertEquals(listOf<Int?>(null), errors)
    }

    @Test
    fun `a CCCD write that does not start fails`() {
        link.execute(LinkCommand.ConnectDirect(addr))
        handle.accepts = false
        link.execute(LinkCommand.EnableHrNotify)
        assertEquals(listOf(LinkEvent.OpFailed), events)
    }

    @Test
    fun `a disconnect drops the queued operations`() {
        link.execute(LinkCommand.ConnectDirect(addr))
        link.execute(LinkCommand.Discover)
        link.execute(LinkCommand.EnableHrNotify)
        callback.onConnectionStateChange(19, connected = false)
        scheduler.advanceBy(60_000)
        assertEquals(listOf(LinkEvent.Disconnected(19)), events)
        assertEquals(listOf("discover"), handle.calls)
    }

    @Test
    fun `heart rate notifications become samples with the time they came`() {
        setUpConnection()
        nanos = 42_000L
        callback.onCharacteristicChanged(HEART_RATE_MEASUREMENT, byteArrayOf(0, 61))
        assertEquals(listOf(LinkEvent.Sample), events)
        assertEquals(listOf(listOf<Byte>(0, 61) to 42_000L), heartRates)
    }

    @Test
    fun `battery is read and reported`() {
        setUpConnection()
        link.execute(LinkCommand.ReadBattery)
        assertEquals("read:2a19", handle.calls.last())
        callback.onCharacteristicRead(BATTERY_LEVEL, byteArrayOf(80), GATT_SUCCESS)
        assertEquals(listOf<Int?>(80), batteries)
        // Not notifying: nothing more is asked
        assertEquals("read:2a19", handle.calls.last())
    }

    @Test
    fun `a notifying battery level is subscribed after the read`() {
        connector.characteristics = FakeGattConnector.HRS_AND_NOTIFYING_BATTERY
        setUpConnection()
        link.execute(LinkCommand.ReadBattery)
        callback.onCharacteristicRead(BATTERY_LEVEL, byteArrayOf(80), GATT_SUCCESS)
        assertEquals("notify:2a19", handle.calls.last())
        callback.onDescriptorWrite(BATTERY_LEVEL, GATT_SUCCESS)
        callback.onCharacteristicChanged(BATTERY_LEVEL, byteArrayOf(79))
        assertEquals(listOf<Int?>(80, 79), batteries)
        // The battery CCCD is not the heart-rate one
        assertEquals(emptyList<LinkEvent>(), events)
    }

    @Test
    fun `no battery service reports no battery`() {
        connector.characteristics = FakeGattConnector.HRS_ONLY
        setUpConnection()
        link.execute(LinkCommand.ReadBattery)
        assertEquals(listOf<Int?>(null), batteries)
        assertFalse(handle.calls.any { it.startsWith("read") })
    }

    @Test
    fun `a failed battery read is harmless`() {
        setUpConnection()
        link.execute(LinkCommand.ReadBattery)
        callback.onCharacteristicRead(BATTERY_LEVEL, byteArrayOf(), 137)
        assertEquals(listOf<Int?>(null), batteries)
        assertEquals(listOf<Int?>(137), errors)
        assertEquals(emptyList<LinkEvent>(), events)
    }

    @Test
    fun `an implausible battery value is no value`() {
        setUpConnection()
        link.execute(LinkCommand.ReadBattery)
        callback.onCharacteristicRead(BATTERY_LEVEL, byteArrayOf(101), GATT_SUCCESS)
        assertEquals(listOf<Int?>(null), batteries)
    }

    @Test
    fun `timers fire back into the machine`() {
        link.execute(LinkCommand.Schedule(LinkTimer.RETRY, 1_000))
        scheduler.advanceBy(999)
        assertEquals(emptyList<LinkEvent>(), events)
        scheduler.advanceBy(1)
        assertEquals(listOf(LinkEvent.Timeout(LinkTimer.RETRY)), events)
    }

    @Test
    fun `a timer is replaced or cancelled by id`() {
        link.execute(LinkCommand.Schedule(LinkTimer.SILENCE, 1_000))
        link.execute(LinkCommand.Schedule(LinkTimer.SILENCE, 5_000))
        link.execute(LinkCommand.Schedule(LinkTimer.CONNECT, 2_000))
        link.execute(LinkCommand.Cancel(LinkTimer.CONNECT))
        scheduler.advanceBy(10_000)
        assertEquals(listOf(LinkEvent.Timeout(LinkTimer.SILENCE)), events)
    }

    @Test
    fun `adapter switches become Bluetooth events`() {
        adapter.emit(BluetoothStates.STATE_TURNING_OFF)
        adapter.emit(BluetoothStates.STATE_OFF)
        adapter.emit(BluetoothStates.STATE_TURNING_ON)
        adapter.emit(BluetoothStates.STATE_ON)
        assertEquals(listOf(LinkEvent.BtOff, LinkEvent.BtOff, LinkEvent.BtOn), events)
    }

    @Test
    fun `stop closes and stops watching the adapter`() {
        link.execute(LinkCommand.ConnectDirect(addr))
        link.execute(LinkCommand.Schedule(LinkTimer.CONNECT, 30_000))
        link.stop()
        assertTrue(handle.closed)
        assertFalse(adapter.watching)
        scheduler.advanceBy(60_000)
        assertEquals(emptyList<LinkEvent>(), events)
    }

    @Test
    fun `device name comes from the open connection`() {
        assertNull(link.deviceName)
        link.execute(LinkCommand.ConnectDirect(addr))
        assertEquals("HR Strap 10 1234", link.deviceName)
    }

    @Test
    fun `the listener is never called inside execute`() {
        connector.accepts = false
        var inside = false
        var calledInside = false
        val reentrant = BleGattLink(connector, adapter, scheduler, elapsedNanos = { 0L })
        reentrant.start(object : GattLink.Listener {
            override fun onEvent(event: LinkEvent) {
                calledInside = calledInside || inside
            }
            override fun onHeartRate(value: ByteArray, elapsedNanos: Long) = Unit
            override fun onBattery(percent: Int?) = Unit
            override fun onGattError(status: Int?) = Unit
        })
        scheduler.post {
            inside = true
            reentrant.execute(LinkCommand.ConnectDirect(addr))
            inside = false
        }
        assertFalse(calledInside)
    }
}

class FakeAdapterWatch : AdapterStateWatch {
    private var onState: ((Int) -> Unit)? = null
    val watching: Boolean get() = onState != null

    override fun start(onState: (Int) -> Unit) {
        this.onState = onState
    }

    override fun stop() {
        onState = null
    }

    fun emit(state: Int) {
        onState?.invoke(state)
    }
}
