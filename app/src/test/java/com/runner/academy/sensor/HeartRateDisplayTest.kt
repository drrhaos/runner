package com.runner.academy.sensor

import com.runner.academy.sensor.HeartRateDisplay.Freshness
import com.runner.academy.sensor.HeartRateDisplay.LinkIcon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HeartRateDisplayTest {

    private val s = 1_000_000_000L
    private val now = 500 * s

    private fun at(secondsAgo: Int, bpm: Int? = 142) = HeartRateSample(now - secondsAgo * s, bpm)

    @Test
    fun `four seconds old is the value`() {
        val display = HeartRateDisplay.of(at(4), SensorLink.CONNECTED, now)

        assertEquals(HeartRateDisplay(142, Freshness.FRESH, LinkIcon.CONNECTED, noContact = false), display)
    }

    @Test
    fun `ten seconds old is the last value greyed`() {
        val display = HeartRateDisplay.of(at(10), SensorLink.CONNECTED, now)

        assertEquals(142, display.bpm)
        assertEquals(Freshness.STALE, display.freshness)
        assertEquals(LinkIcon.CONNECTED, display.link)
    }

    @Test
    fun `twenty seconds old is no value and a lost link`() {
        val display = HeartRateDisplay.of(at(20), SensorLink.CONNECTED, now)

        assertNull(display.bpm)
        assertEquals(Freshness.NONE, display.freshness)
        assertEquals(LinkIcon.LOST, display.link)
    }

    @Test
    fun `no contact is no value at once with the link still connected`() {
        val display = HeartRateDisplay.of(at(1, bpm = null), SensorLink.CONNECTED, now)

        assertNull(display.bpm)
        assertEquals(Freshness.NONE, display.freshness)
        assertEquals(LinkIcon.CONNECTED, display.link)
        assertTrue(display.noContact)
    }

    @Test
    fun `an old no-contact sample is a lost link, not a strap off`() {
        val display = HeartRateDisplay.of(at(20, bpm = null), SensorLink.CONNECTED, now)

        assertFalse(display.noContact)
        assertEquals(LinkIcon.LOST, display.link)
    }

    @Test
    fun `searching keeps a fresh value and shows the searching icon`() {
        for (link in listOf(SensorLink.CONNECTING, SensorLink.SEARCHING)) {
            val display = HeartRateDisplay.of(at(2), link, now)
            assertEquals(LinkIcon.SEARCHING, display.link)
            assertEquals(142, display.bpm)
            assertFalse(display.noContact)
        }
    }

    @Test
    fun `bluetooth off, no permission and idle are lost`() {
        for (link in listOf(SensorLink.BLUETOOTH_OFF, SensorLink.NO_PERMISSION, SensorLink.IDLE)) {
            assertEquals(LinkIcon.LOST, HeartRateDisplay.of(null, link, now).link)
        }
    }

    @Test
    fun `connected before the first sample is connected without a value`() {
        val display = HeartRateDisplay.of(null, SensorLink.CONNECTED, now)

        assertNull(display.bpm)
        assertEquals(LinkIcon.CONNECTED, display.link)
        assertFalse(display.noContact)
    }

    @Test
    fun `a no-contact sample while searching is not a strap off`() {
        assertFalse(HeartRateDisplay.of(at(1, bpm = null), SensorLink.SEARCHING, now).noContact)
    }

    @Test
    fun `the edges are five and fifteen seconds`() {
        assertEquals(Freshness.FRESH, HeartRateDisplay.of(at(5), SensorLink.CONNECTED, now).freshness)
        assertEquals(Freshness.STALE, HeartRateDisplay.of(at(15), SensorLink.CONNECTED, now).freshness)
        assertEquals(Freshness.NONE, HeartRateDisplay.of(at(16), SensorLink.CONNECTED, now).freshness)
    }
}
