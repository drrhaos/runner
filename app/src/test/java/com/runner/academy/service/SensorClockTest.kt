package com.runner.academy.service

import org.junit.Assert.assertEquals
import org.junit.Test

class SensorClockTest {

    private val sec = 1_000_000_000L

    /** The phone slept 30 s since boot: uptime runs 30 s behind elapsed realtime. */
    private val elapsed = 500 * sec
    private val uptime = 470 * sec

    @Test
    fun `an event on elapsed realtime is read as it is`() {
        val clock = SensorClock.detect(eventNanos = elapsed - sec / 10, elapsedNanos = elapsed, uptimeNanos = uptime)

        assertEquals(SensorClock.ELAPSED_REALTIME, clock)
        assertEquals(400 * sec, clock.toElapsed(400 * sec, elapsed, uptime))
    }

    @Test
    fun `an event on uptime is moved by the sleep onto elapsed realtime`() {
        val clock = SensorClock.detect(eventNanos = uptime - sec / 10, elapsedNanos = elapsed, uptimeNanos = uptime)

        assertEquals(SensorClock.UPTIME, clock)
        // 10 s before now on the uptime clock is 10 s before now on elapsed realtime
        assertEquals(elapsed - 10 * sec, clock.toElapsed(uptime - 10 * sec, elapsed, uptime))
    }

    @Test
    fun `a clock 30 s off both is not trusted, the delivery time is used`() {
        val clock = SensorClock.detect(eventNanos = elapsed - 30 * sec - 30 * sec, elapsedNanos = elapsed, uptimeNanos = uptime)

        assertEquals(SensorClock.DELIVERY, clock)
        assertEquals(elapsed, clock.toElapsed(123L, elapsed, uptime))
    }

    @Test
    fun `garbage timestamps mean the delivery time`() {
        for (event in listOf(0L, -5L, Long.MAX_VALUE, Long.MIN_VALUE, 1L)) {
            assertEquals("$event", SensorClock.DELIVERY, SensorClock.detect(event, elapsed, uptime))
        }
    }

    @Test
    fun `without any sleep both clocks agree and elapsed realtime is taken`() {
        assertEquals(SensorClock.ELAPSED_REALTIME, SensorClock.detect(elapsed, elapsed, elapsed))
    }
}
