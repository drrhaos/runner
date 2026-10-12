package com.runner.academy.ui.tracking

import com.runner.academy.R
import com.runner.academy.sensor.HeartRateDisplay
import com.runner.academy.sensor.HeartRateDisplay.Freshness
import com.runner.academy.sensor.HeartRateDisplay.LinkIcon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HeartRateTileLookTest {

    @Test
    fun `no sensor is dashes without an icon`() {
        val look = HeartRateTileLook.of(null)

        assertEquals("--", look.text)
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

        assertEquals("--", look.text)
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

        assertEquals("--", look.text)
        assertEquals(HrLinkIcon.NO_CONTACT, look.icon)
        assertEquals(R.drawable.ic_hr_link_connected, look.icon!!.drawable)
        assertEquals(com.google.android.material.R.attr.colorError, look.icon!!.tintAttr)
        assertEquals(R.string.devices_status_no_contact, look.icon!!.stateDescription)
    }
}
