package com.runner.academy.ui.devices

import com.runner.academy.R
import com.runner.academy.sensor.HeartRateSample
import com.runner.academy.sensor.PairedSensor
import com.runner.academy.sensor.SensorLink
import com.runner.academy.sensor.SensorStatus
import com.runner.academy.sensor.SensorType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceRowTextTest {

    private val strap = PairedSensor(
        type = SensorType.HEART_RATE,
        address = "AA:BB:CC:DD:EE:FF",
        name = "HR Strap 10",
        pairedAt = 1_000L,
        lastSeenAt = 5_000L,
        batteryPercent = 70
    )

    private fun row(
        link: SensorLink,
        battery: Int? = null,
        lastSeenAt: Long? = null,
        sample: HeartRateSample? = null,
        paired: PairedSensor = strap,
        sinceLinkMs: Long = 0L
    ) = DeviceRowText.of(paired, SensorStatus(link, battery, lastSeenAt), sample, nowMs = 100_000L + sinceLinkMs, linkSinceMs = 100_000L)

    @Test
    fun `connected with a pulse shows it, in the primary colour, with the battery`() {
        val row = row(SensorLink.CONNECTED, battery = 80, sample = HeartRateSample(1L, 142))

        assertEquals(R.string.devices_status_connected, row.status)
        assertEquals(142, row.bpm)
        assertEquals(DeviceTone.PRIMARY, row.tone)
        assertEquals(80, row.batteryPercent)
        assertNull(row.lastSeenAt)
        assertNull(row.action)
        assertEquals("HR Strap 10", row.name)
    }

    @Test
    fun `connected without skin contact says the strap is not worn, as an error`() {
        val row = row(SensorLink.CONNECTED, sample = HeartRateSample(1L, null))

        assertEquals(R.string.devices_status_no_contact, row.status)
        assertNull(row.bpm)
        assertEquals(DeviceTone.ERROR, row.tone)
    }

    @Test
    fun `connected before the first sample is just connected`() {
        val row = row(SensorLink.CONNECTED)

        assertEquals(R.string.devices_status_connected, row.status)
        assertNull(row.bpm)
        assertEquals(DeviceTone.PRIMARY, row.tone)
    }

    @Test
    fun `the battery falls back to the stored one`() {
        assertEquals(70, row(SensorLink.CONNECTED).batteryPercent)
        assertEquals(90, row(SensorLink.CONNECTED, battery = 90).batteryPercent)
    }

    @Test
    fun `battery at 15 percent or below is low, above is not`() {
        assertTrue(row(SensorLink.CONNECTED, battery = 15).batteryLow)
        assertFalse(row(SensorLink.CONNECTED, battery = 16).batteryLow)
        assertFalse(row(SensorLink.CONNECTED, paired = strap.copy(batteryPercent = null)).batteryLow)
    }

    @Test
    fun `connecting or searching is searching for the first 30 s`() {
        for (link in listOf(SensorLink.CONNECTING, SensorLink.SEARCHING)) {
            val row = row(link, battery = 60, sinceLinkMs = 29_999L)
            assertEquals(R.string.devices_status_searching, row.status)
            assertEquals(DeviceTone.NORMAL, row.tone)
            assertEquals(60, row.batteryPercent)
            assertNull(row.lastSeenAt)
        }
    }

    @Test
    fun `searching for 30 s or more with a last contact is offline, with when it was seen`() {
        val row = row(SensorLink.SEARCHING, battery = 60, lastSeenAt = 9_000L, sinceLinkMs = 30_000L)

        assertEquals(R.string.devices_status_offline, row.status)
        assertEquals(DeviceTone.NORMAL, row.tone)
        assertEquals(9_000L, row.lastSeenAt)
        assertNull(row.batteryPercent)
        assertNull(row.bpm)
    }

    @Test
    fun `the last contact falls back to the stored one`() {
        assertEquals(5_000L, row(SensorLink.IDLE).lastSeenAt)
    }

    @Test
    fun `idle is offline at once`() {
        assertEquals(R.string.devices_status_offline, row(SensorLink.IDLE, sinceLinkMs = 0L).status)
    }

    @Test
    fun `never seen says so, without meta`() {
        val never = strap.copy(lastSeenAt = null)

        val idle = row(SensorLink.IDLE, paired = never)
        val searching = row(SensorLink.SEARCHING, paired = never, sinceLinkMs = 31_000L)

        for (row in listOf(idle, searching)) {
            assertEquals(R.string.devices_status_never_seen, row.status)
            assertNull(row.lastSeenAt)
            assertNull(row.batteryPercent)
        }
    }

    @Test
    fun `bluetooth off offers to turn it on`() {
        val row = row(SensorLink.BLUETOOTH_OFF, battery = 50)

        assertEquals(R.string.devices_status_bluetooth_off, row.status)
        assertEquals(DeviceTone.ERROR, row.tone)
        assertEquals(DeviceAction.ENABLE_BLUETOOTH, row.action)
        assertNull(row.batteryPercent)
        assertNull(row.lastSeenAt)
    }

    @Test
    fun `no permission offers to allow`() {
        val row = row(SensorLink.NO_PERMISSION)

        assertEquals(R.string.devices_status_no_permission, row.status)
        assertEquals(DeviceTone.ERROR, row.tone)
        assertEquals(DeviceAction.ALLOW, row.action)
    }

    @Test
    fun `a pulse is shown only while connected`() {
        val row = row(SensorLink.SEARCHING, sample = HeartRateSample(1L, 120))

        assertNull(row.bpm)
    }

    @Test
    fun `a device without a name has none`() {
        assertNull(row(SensorLink.IDLE, paired = strap.copy(name = null)).name)
        assertNull(row(SensorLink.IDLE, paired = strap.copy(name = "  ")).name)
    }
}
