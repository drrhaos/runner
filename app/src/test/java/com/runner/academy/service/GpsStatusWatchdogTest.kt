package com.runner.academy.service

import com.runner.academy.data.GpsStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GpsStatusWatchdogTest {

    private val timeout = 5_000L

    private fun resolve(current: GpsStatus, now: Long, lastGood: Long, lastAny: Long) =
        GpsStatusWatchdog.resolve(current, now, lastGood, lastAny, timeout)

    @Test
    fun `unreliable stays while false fixes keep arriving`() {
        assertNull(resolve(GpsStatus.UNRELIABLE, now = 120_000, lastGood = 10_000, lastAny = 119_000))
    }

    @Test
    fun `unreliable stays before the first good fix of a false start`() {
        assertNull(resolve(GpsStatus.UNRELIABLE, now = 60_000, lastGood = 0, lastAny = 59_000))
    }

    @Test
    fun `real silence during an unreliable signal is lost`() {
        assertEquals(GpsStatus.LOST, resolve(GpsStatus.UNRELIABLE, now = 120_000, lastGood = 10_000, lastAny = 100_000))
    }

    @Test
    fun `silence after a false start without a good fix is searching`() {
        assertEquals(GpsStatus.SEARCHING, resolve(GpsStatus.UNRELIABLE, now = 120_000, lastGood = 0, lastAny = 100_000))
    }

    @Test
    fun `release-1 rules are unchanged`() {
        assertEquals(GpsStatus.LOST, resolve(GpsStatus.FOUND, now = 30_000, lastGood = 10_000, lastAny = 10_000))
        assertNull(resolve(GpsStatus.FOUND, now = 20_000, lastGood = 10_000, lastAny = 10_000))
        assertEquals(GpsStatus.FOUND, resolve(GpsStatus.LOST, now = 12_000, lastGood = 10_000, lastAny = 10_000))
        assertEquals(GpsStatus.SEARCHING, resolve(GpsStatus.FOUND, now = 12_000, lastGood = 0, lastAny = 0))
        assertNull(resolve(GpsStatus.SEARCHING, now = 12_000, lastGood = 0, lastAny = 0))
    }
}
