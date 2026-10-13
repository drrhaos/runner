package com.runner.academy.service

import com.runner.academy.sensor.FakeHeartRateSensor
import com.runner.academy.sensor.HeartRateDisplay
import com.runner.academy.sensor.SensorLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HeartRateTrackerTest {

    private val s = 1_000_000_000L
    private var now = 1_000 * s
    private val sensor = FakeHeartRateSensor(SensorLink.CONNECTED)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val tracker = HeartRateTracker(sensor, clockNanos = { now }, scope = scope)

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `start takes the workout lease, stop gives it back`() {
        tracker.start()
        assertTrue(tracker.isRunning)
        assertEquals(listOf("workout"), sensor.owners)

        tracker.stop()
        assertFalse(tracker.isRunning)
        assertEquals(0, sensor.leaseCount)
    }

    @Test
    fun `a second start keeps one lease`() {
        tracker.start()
        tracker.start()

        assertEquals(1, sensor.leaseCount)
    }

    @Test
    fun `samples go into the history while running`() {
        tracker.start()
        sensor.emit(150, atNanos = now - 2 * s)

        assertEquals(150, tracker.bpmAt(now))
        assertEquals(HeartRateDisplay(150, HeartRateDisplay.Freshness.FRESH, HeartRateDisplay.LinkIcon.CONNECTED), tracker.display())
    }

    @Test
    fun `stop forgets the history and ignores later samples`() {
        tracker.start()
        sensor.emit(150, atNanos = now)
        tracker.stop()
        sensor.emit(160, atNanos = now)

        assertNull(tracker.bpmAt(now))
        assertNull(tracker.display().bpm)
    }

    @Test
    fun `not running gives no value`() {
        sensor.emit(150, atNanos = now)

        assertNull(tracker.bpmAt(now))
    }

    @Test
    fun `the last valid sample is kept past the history and forgotten on stop`() {
        tracker.start()
        assertNull(tracker.lastValidNanos)
        sensor.emit(150, atNanos = now)
        sensor.emit(null, atNanos = now + s)
        assertEquals(now, tracker.lastValidNanos)

        // Three minutes of no contact: the history drops the valid one, the time stays
        for (i in 2..180) sensor.emit(null, atNanos = now + i * s)
        assertEquals(now, tracker.lastValidNanos)

        tracker.stop()
        assertNull(tracker.lastValidNanos)
    }

    @Test
    fun `idle under the workout lease is searching, not lost`() {
        sensor.setLink(SensorLink.IDLE)
        tracker.start()

        assertEquals(HeartRateDisplay.LinkIcon.SEARCHING, tracker.display().link)
    }

    @Test
    fun `the display follows the clock and the link`() {
        tracker.start()
        sensor.emit(150, atNanos = now)
        now += 10 * s
        assertEquals(HeartRateDisplay.Freshness.STALE, tracker.display().freshness)

        sensor.setLink(SensorLink.SEARCHING)
        assertEquals(HeartRateDisplay.LinkIcon.SEARCHING, tracker.display().link)
    }
}
