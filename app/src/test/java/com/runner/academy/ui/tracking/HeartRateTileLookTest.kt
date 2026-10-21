package com.runner.academy.ui.tracking

import com.runner.academy.R
import com.runner.academy.sensor.HeartRateDisplay
import com.runner.academy.sensor.HeartRateDisplay.Freshness
import com.runner.academy.sensor.HeartRateDisplay.LinkIcon
import com.runner.academy.util.HrZones
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HeartRateTileLookTest {

    @Test
    fun `no sensor is dashes without an icon`() {
        val look = HeartRateTileLook.of(null)

        assertNull(look.text)
        assertFalse(look.greyed)
        assertNull(look.icon)
        assertEquals(HeartRateTileLook.A11y(R.string.tracking_hr_tile_no_sensor_a11y), look.a11y)
    }

    @Test
    fun `a fresh value is plain with the connected icon`() {
        val look = HeartRateTileLook.of(HeartRateDisplay(142, Freshness.FRESH, LinkIcon.CONNECTED))

        assertEquals("142", look.text)
        assertFalse(look.greyed)
        assertEquals(HrLinkIcon.CONNECTED, look.icon)
        assertEquals(HeartRateTileLook.A11y(R.string.tracking_hr_tile_a11y, bpm = 142), look.a11y)
        assertEquals(R.string.tracking_hr_link_connected, look.icon!!.stateDescription)
    }

    @Test
    fun `a stale value is greyed and said to be not updating`() {
        val look = HeartRateTileLook.of(HeartRateDisplay(142, Freshness.STALE, LinkIcon.CONNECTED))

        assertEquals("142", look.text)
        assertTrue(look.greyed)
        assertEquals(HeartRateTileLook.A11y(R.string.tracking_hr_tile_stale_a11y, bpm = 142), look.a11y)
    }

    @Test
    fun `no value is dashes in the normal colour`() {
        val look = HeartRateTileLook.of(HeartRateDisplay(null, Freshness.NONE, LinkIcon.LOST))

        assertNull(look.text)
        assertFalse(look.greyed)
        assertEquals(HrLinkIcon.LOST, look.icon)
        assertEquals(HeartRateTileLook.A11y(R.string.tracking_hr_tile_no_data_a11y), look.a11y)
        assertEquals(R.string.tracking_hr_link_lost, look.icon!!.stateDescription)
    }

    @Test
    fun `searching has its own icon`() {
        val look = HeartRateTileLook.of(HeartRateDisplay(null, Freshness.NONE, LinkIcon.SEARCHING))

        assertEquals(HrLinkIcon.SEARCHING, look.icon)
        assertEquals(R.string.tracking_hr_link_searching, look.icon!!.stateDescription)
    }

    @Test
    fun `no contact is the connected icon in the error colour, said as strap not worn`() {
        val look = HeartRateTileLook.of(HeartRateDisplay(null, Freshness.NONE, LinkIcon.CONNECTED, noContact = true))

        assertNull(look.text)
        assertEquals(HrLinkIcon.NO_CONTACT, look.icon)
        assertEquals(R.drawable.ic_hr_link_connected, look.icon!!.drawable)
        assertEquals(com.google.android.material.R.attr.colorError, look.icon!!.tintAttr)
        assertEquals(R.string.devices_status_no_contact, look.icon!!.stateDescription)
    }

    // --- Zone badge (HRmax 190: Z1 95, Z4 152, Z5 171) ---

    private val zones = HrZones(190)

    @Test
    fun `without zones there is no zone`() {
        val look = HeartRateTileLook.of(HeartRateDisplay(165, Freshness.FRESH, LinkIcon.CONNECTED), null)

        assertNull(look.zone)
        assertNull(look.badge)
        assertEquals(HeartRateTileLook.A11y(R.string.tracking_hr_tile_a11y, bpm = 165), look.a11y)
    }

    @Test
    fun `a fresh value shows its zone in the zone colour and says it`() {
        val look = HeartRateTileLook.of(HeartRateDisplay(165, Freshness.FRESH, LinkIcon.CONNECTED), zones)

        assertEquals(4, look.zone)
        assertEquals(HeartRateTileLook.ZoneBadge(4, greyed = false), look.badge)
        assertEquals(HeartRateTileLook.A11y(R.string.tracking_hr_tile_zone_a11y, bpm = 165, zone = 4), look.a11y)
    }

    @Test
    fun `a stale value keeps its zone, greyed`() {
        val look = HeartRateTileLook.of(HeartRateDisplay(165, Freshness.STALE, LinkIcon.CONNECTED), zones)

        assertEquals(4, look.zone)
        assertEquals(HeartRateTileLook.ZoneBadge(4, greyed = true), look.badge)
        assertEquals(HeartRateTileLook.A11y(R.string.tracking_hr_tile_stale_zone_a11y, bpm = 165, zone = 4), look.a11y)
    }

    @Test
    fun `a fresh value below Z1 shows the grey below badge`() {
        val look = HeartRateTileLook.of(HeartRateDisplay(80, Freshness.FRESH, LinkIcon.CONNECTED), zones)

        assertEquals(0, look.zone)
        assertEquals(HeartRateTileLook.ZoneBadge(0, greyed = true), look.badge)
        assertEquals(HeartRateTileLook.A11y(R.string.tracking_hr_tile_below_zone_a11y, bpm = 80), look.a11y)
    }

    @Test
    fun `a stale value below Z1 has no badge and the plain stale text`() {
        val look = HeartRateTileLook.of(HeartRateDisplay(80, Freshness.STALE, LinkIcon.CONNECTED), zones)

        assertEquals(0, look.zone)
        assertNull(look.badge)
        assertEquals(HeartRateTileLook.A11y(R.string.tracking_hr_tile_stale_a11y, bpm = 80), look.a11y)
    }

    @Test
    fun `dashes have no zone`() {
        val look = HeartRateTileLook.of(HeartRateDisplay(null, Freshness.NONE, LinkIcon.LOST), zones)

        assertNull(look.zone)
        assertNull(look.badge)
        assertNull(HeartRateTileLook.of(null, zones).badge)
    }
}
