package com.runner.academy.sensor

import android.content.Context
import androidx.core.content.edit
import com.runner.academy.sensor.LinkCommand.Close
import com.runner.academy.sensor.LinkCommand.ConnectAuto
import com.runner.academy.sensor.LinkCommand.ConnectDirect
import com.runner.academy.sensor.LinkCommand.Discover
import com.runner.academy.sensor.LinkCommand.EnableHrNotify
import com.runner.academy.sensor.LinkCommand.ReadBattery
import com.runner.academy.sensor.SensorDiagEvent.Kind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BleHeartRateSensorTest {

    private val addr = "AA:BB:CC:DD:EE:FF"
    private val strap = PairedSensor(SensorType.HEART_RATE, addr, "HR Strap 10", 1_000L, lastSeenAt = 2_000L, batteryPercent = 90)

    private lateinit var store: PairedSensorStore
    private val link = FakeGattLink()
    private val scheduler = FakeLinkScheduler()
    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private var permitted = true
    private var permissionChecks = 0
    private var bluetoothOn = true
    private var wallMs = 1_700_000_000_000L

    private val samples = mutableListOf<HeartRateSample>()
    private val diag = mutableListOf<SensorDiagEvent>()

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(PairedSensorStore.PREFS_NAME, Context.MODE_PRIVATE).edit { clear() }
        store = PairedSensorStore(context)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun sensor(): BleHeartRateSensor =
        BleHeartRateSensor(
            store = store,
            link = link,
            scheduler = scheduler,
            canConnect = { permissionChecks++; permitted },
            bluetoothOn = { bluetoothOn },
            nowMs = { scheduler.nowMs },
            wallMs = { wallMs },
            scope = scope
        ).also { s ->
            s.samples.onEach { samples += it }.launchIn(scope)
            s.diagnostics.onEach { diag += it }.launchIn(scope)
        }

    /** Acquired and set up: notifications on, battery asked. */
    private fun connected(s: BleHeartRateSensor): SensorLease {
        val lease = s.acquire("test")
        link.event(LinkEvent.Connected)
        link.event(LinkEvent.ServicesOk(hasHrs = true))
        link.event(LinkEvent.NotifyEnabled)
        link.drain()
        diag.clear()
        return lease
    }

    @Test
    fun `without a lease the radio is quiet`() {
        store.put(strap)
        val s = sensor()
        assertEquals(emptyList<LinkCommand>(), link.commands)
        assertEquals(SensorStatus(SensorLink.IDLE, 90, 2_000L), s.status.value)
    }

    @Test
    fun `the first lease connects to the paired device`() {
        store.put(strap)
        val s = sensor()
        s.acquire("devices")
        val commands = link.drain()
        assertEquals(listOf(Close, ConnectDirect(addr)), commands.filter { it is Close || it is ConnectDirect })
        assertEquals(SensorLink.CONNECTING, s.status.value.link)
        assertEquals(listOf(SensorDiagEvent(Kind.CONNECTING)), diag)
    }

    @Test
    fun `with nothing paired a lease does nothing`() {
        val s = sensor()
        s.acquire("devices")
        assertFalse(link.commands.any { it is ConnectDirect })
        assertEquals(SensorStatus(SensorLink.IDLE), s.status.value)
    }

    @Test
    fun `callbacks drive the setup to connected`() {
        store.put(strap)
        val s = sensor()
        s.acquire("devices")
        link.drain()
        link.event(LinkEvent.Connected)
        assertTrue(Discover in link.drain())
        link.event(LinkEvent.ServicesOk(hasHrs = true))
        assertEquals(listOf(EnableHrNotify), link.drain())
        link.event(LinkEvent.NotifyEnabled)
        assertEquals(listOf(ReadBattery), link.drain())
        assertEquals(SensorLink.CONNECTED, s.status.value.link)
        assertEquals(listOf(Kind.CONNECTING, Kind.CONNECTED), diag.map { it.kind })
    }

    @Test
    fun `leases are counted, the last release closes`() {
        store.put(strap)
        val s = sensor()
        val a = s.acquire("devices")
        val b = s.acquire("workout")
        assertEquals(1, link.drain().count { it is ConnectDirect })
        assertEquals(2, s.leaseCount)

        a.release()
        a.release()
        assertEquals(1, s.leaseCount)
        assertEquals(emptyList<LinkCommand>(), link.drain())

        b.release()
        assertEquals(0, s.leaseCount)
        assertTrue(Close in link.drain())
        assertEquals(SensorLink.IDLE, s.status.value.link)

        s.acquire("again")
        assertEquals(1, link.drain().count { it is ConnectDirect })
    }

    @Test
    fun `heart rate packets become samples at their time`() {
        store.put(strap)
        val s = sensor()
        connected(s)
        link.listener!!.onHeartRate(byteArrayOf(0x16, 72), 5_000L)
        // Contact supported but not detected
        link.listener!!.onHeartRate(byteArrayOf(0x04, 72), 6_000L)
        // Not a valid packet: no sample
        link.listener!!.onHeartRate(byteArrayOf(), 7_000L)
        assertEquals(listOf(HeartRateSample(5_000L, 72), HeartRateSample(6_000L, null)), samples)
    }

    @Test
    fun `samples update last seen, saved at most once a minute`() {
        store.put(strap)
        val s = sensor()
        connected(s)
        link.listener!!.onHeartRate(byteArrayOf(0, 70), 1L)
        assertEquals(wallMs, s.status.value.lastSeenAt)
        assertEquals(wallMs, store.get(SensorType.HEART_RATE)!!.lastSeenAt)
        val firstSaved = wallMs

        wallMs += 30_000
        link.listener!!.onHeartRate(byteArrayOf(0, 70), 2L)
        assertEquals(wallMs, s.status.value.lastSeenAt)
        assertEquals(firstSaved, store.get(SensorType.HEART_RATE)!!.lastSeenAt)

        wallMs += 30_000
        link.listener!!.onHeartRate(byteArrayOf(0, 70), 3L)
        assertEquals(wallMs, store.get(SensorType.HEART_RATE)!!.lastSeenAt)
    }

    @Test
    fun `a drop saves the last seen time`() {
        store.put(strap)
        val s = sensor()
        connected(s)
        link.listener!!.onHeartRate(byteArrayOf(0, 70), 1L)
        wallMs += 10_000
        link.listener!!.onHeartRate(byteArrayOf(0, 70), 2L)
        link.event(LinkEvent.Disconnected(8))
        assertEquals(wallMs, store.get(SensorType.HEART_RATE)!!.lastSeenAt)
        assertEquals(SensorDiagEvent(Kind.DISCONNECTED, 8), diag.first())
        assertEquals(SensorLink.CONNECTING, s.status.value.link)
    }

    @Test
    fun `battery goes to the status, the store and the log`() {
        store.put(strap)
        val s = sensor()
        connected(s)
        link.listener!!.onBattery(75)
        assertEquals(75, s.status.value.batteryPercent)
        assertEquals(wallMs, s.status.value.lastSeenAt)
        assertEquals(75, store.get(SensorType.HEART_RATE)!!.batteryPercent)
        assertEquals(listOf(SensorDiagEvent(Kind.BATTERY, 75)), diag)

        // No value keeps the last known one
        link.listener!!.onBattery(null)
        assertEquals(75, s.status.value.batteryPercent)
        assertEquals(1, diag.size)
    }

    @Test
    fun `without permission the status says so and nothing connects`() {
        store.put(strap)
        permitted = false
        val s = sensor()
        assertEquals(SensorLink.NO_PERMISSION, s.status.value.link)
        s.acquire("devices")
        assertFalse(link.commands.any { it is ConnectDirect })
        assertEquals(SensorLink.NO_PERMISSION, s.status.value.link)

        // Granted: the next acquire checks again and connects for both leases
        permitted = true
        s.acquire("devices")
        assertEquals(1, link.drain().count { it is ConnectDirect })
        assertEquals(SensorLink.CONNECTING, s.status.value.link)
    }

    @Test
    fun `Bluetooth off and on`() {
        store.put(strap)
        val s = sensor()
        connected(s)
        link.event(LinkEvent.BtOff)
        link.event(LinkEvent.BtOff)
        assertTrue(Close in link.drain())
        assertEquals(SensorLink.BLUETOOTH_OFF, s.status.value.link)

        link.event(LinkEvent.BtOn)
        assertTrue(link.drain().any { it is ConnectDirect })
        assertEquals(listOf(Kind.BT_OFF, Kind.BT_ON, Kind.CONNECTING), diag.map { it.kind })
    }

    @Test
    fun `starts off when Bluetooth is off`() {
        store.put(strap)
        bluetoothOn = false
        val s = sensor()
        s.acquire("devices")
        assertFalse(link.commands.any { it is ConnectDirect })
        assertEquals(SensorLink.BLUETOOTH_OFF, s.status.value.link)
        assertEquals(emptyList<SensorDiagEvent>(), diag)
    }

    @Test
    fun `the link and the permission are touched only on the BLE thread`() {
        store.put(strap)
        scheduler.hold()
        val s = sensor()
        assertNull(link.listener)
        assertEquals(0, permissionChecks)

        scheduler.release()
        assertTrue(link.listener != null)
        assertEquals(1, permissionChecks)
        s.acquire("devices")
        assertTrue(link.commands.any { it is ConnectDirect })
    }

    @Test
    fun `a switch before the receiver was registered is not lost`() {
        store.put(strap)
        // On when the sensor was made, off by the time the receiver listens
        link.onStart = { bluetoothOn = false }
        val s = sensor()
        assertEquals(SensorLink.BLUETOOTH_OFF, s.status.value.link)
        s.acquire("devices")
        assertFalse(link.commands.any { it is ConnectDirect })
    }

    @Test
    fun `the sensor lets go of a device being probed and takes it back after`() {
        store.put(strap)
        val s = sensor()
        connected(s)
        val hold = s.holdOff(addr)
        assertTrue(Close in link.drain())
        assertEquals(SensorLink.IDLE, s.status.value.link)

        // Leases taken meanwhile wait too
        s.acquire("workout")
        assertFalse(link.drain().any { it is ConnectDirect })

        hold.release()
        hold.release()
        assertEquals(1, link.drain().count { it is ConnectDirect })
    }

    @Test
    fun `probing another device leaves the sensor alone`() {
        store.put(strap)
        val s = sensor()
        connected(s)
        val hold = s.holdOff("11:22:33:44:55:66")
        hold.release()
        assertEquals(emptyList<LinkCommand>(), link.drain())
        assertEquals(SensorLink.CONNECTED, s.status.value.link)
    }

    @Test
    fun `a replaced device is connected instead, with its own battery`() {
        store.put(strap)
        val s = sensor()
        connected(s)
        val chestStrap = PairedSensor(SensorType.HEART_RATE, "11:22:33:44:55:66", "Chest Strap", 3_000L, null, 50)
        store.put(chestStrap)
        val commands = link.drain()
        assertEquals(listOf(Close, ConnectDirect(chestStrap.address)), commands.filter { it is Close || it is ConnectDirect })
        assertEquals(SensorStatus(SensorLink.CONNECTING, 50, null), s.status.value)
        // From now on the link's data belongs to the new device
        link.listener!!.onBattery(10)
        assertEquals(10, store.get(SensorType.HEART_RATE)!!.batteryPercent)
        assertEquals(chestStrap.address, store.get(SensorType.HEART_RATE)!!.address)
    }

    @Test
    fun `a forgotten device is closed`() {
        store.put(strap)
        val s = sensor()
        connected(s)
        store.remove(SensorType.HEART_RATE)
        assertTrue(Close in link.drain())
        assertEquals(SensorStatus(SensorLink.IDLE), s.status.value)
    }

    @Test
    fun `our own store writes do not restart the link`() {
        store.put(strap)
        val s = sensor()
        connected(s)
        link.listener!!.onBattery(70)
        assertFalse(link.commands.any { it is ConnectDirect || it is Close })
    }

    @Test
    fun `a device without heart rate is given up`() {
        store.put(strap)
        val s = sensor()
        s.acquire("devices")
        link.event(LinkEvent.Connected)
        link.event(LinkEvent.ServicesOk(hasHrs = false))
        assertEquals(SensorLink.IDLE, s.status.value.link)
    }

    @Test
    fun `GATT errors are logged`() {
        store.put(strap)
        val s = sensor()
        connected(s)
        link.listener!!.onGattError(133)
        link.listener!!.onGattError(null)
        assertEquals(listOf(SensorDiagEvent(Kind.GATT_ERROR, 133), SensorDiagEvent(Kind.GATT_ERROR)), diag)
    }

    @Test
    fun `a silent connection is logged when it is dropped`() {
        store.put(strap)
        var now = 0L
        val s = BleHeartRateSensor(store, link, scheduler, { true }, { true }, nowMs = { now }, wallMs = { wallMs }, scope = scope)
        s.diagnostics.onEach { diag += it }.launchIn(scope)
        connected(s)
        now += ConnectionMachine.SILENCE_MS
        link.event(LinkEvent.Timeout(LinkTimer.SILENCE))
        assertEquals(Kind.SILENCE, diag.first().kind)
        assertTrue(Close in link.drain())
    }

    @Test
    fun `after the direct attempts the auto connect is logged`() {
        store.put(strap)
        var now = 0L
        val s = BleHeartRateSensor(store, link, scheduler, { true }, { true }, nowMs = { now }, wallMs = { wallMs }, scope = scope)
        s.diagnostics.onEach { diag += it }.launchIn(scope)
        s.acquire("devices")
        repeat(4) {
            link.event(LinkEvent.Disconnected(133))
            link.event(LinkEvent.Timeout(LinkTimer.RETRY))
        }
        assertTrue(link.commands.any { it is ConnectAuto })
        assertEquals(Kind.AUTO_CONNECT, diag.last().kind)
        assertEquals(SensorLink.SEARCHING, s.status.value.link)
    }

    @Test
    fun `the link is started once`() {
        sensor()
        assertTrue(link.listener != null)
        assertNull(diag.firstOrNull())
    }
}
