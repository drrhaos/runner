package com.runner.academy.debug

import com.runner.academy.sensor.HeartRateSample
import com.runner.academy.sensor.SensorLink
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SimulatedHeartRateScenarioTest {

    private fun at(sec: Int) = SimulatedHeartRateScenario.at(sec * 1_000L)

    @Test
    fun `a normal stretch is a connected sine between 120 and 170`() {
        val values = (0 until 60).map { at(it) }
        assertTrue(values.all { it.link == SensorLink.CONNECTED && it.sample })
        val bpms = values.map { it.bpm!! }
        assertTrue(bpms.all { it in 120..170 })
        assertTrue(bpms.toSet().size > 10)
    }

    @Test
    fun `each state of the tile comes once in four minutes`() {
        // Silence while connected, longer than the 15 s grey value: the value goes grey, then dashes
        for (sec in 60 until 80) {
            assertEquals(SimulatedHeartRateScenario.Step(SensorLink.CONNECTED, null, sample = false), at(sec))
        }
        assertTrue(at(59).sample && at(80).sample)
        // No contact
        assertEquals(SimulatedHeartRateScenario.Step(SensorLink.CONNECTED, null, sample = true), at(125))
        // Searching, long enough for the lost voice
        assertEquals(SimulatedHeartRateScenario.Step(SensorLink.CONNECTING, null, sample = false), at(200))
        assertTrue(at(230).sample && at(230).bpm != null)
    }

    @Test
    fun `the scenario repeats every four minutes`() {
        for (sec in listOf(0, 65, 125, 200, 230)) assertEquals(at(sec), at(sec + 240))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SimulatedHeartRateSensorTest {

    private val scope = TestScope(StandardTestDispatcher())
    private val nanos: Long get() = scope.testScheduler.currentTime * 1_000_000L
    private val sensor = SimulatedHeartRateSensor(scope, clockNanos = { nanos })

    @Test
    fun `samples come once a second only while leased`() {
        val got = mutableListOf<HeartRateSample>()
        sensor.samples.onEach { got += it }.launchIn(scope)
        scope.runCurrent()
        assertEquals(SensorLink.IDLE, sensor.status.value.link)

        val lease = sensor.acquire("test")
        scope.runCurrent()
        scope.advanceTimeBy(5_000L)
        scope.runCurrent()
        assertEquals(SensorLink.CONNECTED, sensor.status.value.link)
        assertEquals(6, got.size)
        assertEquals(1_000_000_000L, got[1].elapsedNanos - got[0].elapsedNanos)

        lease.release()
        scope.runCurrent()
        scope.advanceTimeBy(5_000L)
        assertEquals(6, got.size)
        assertEquals(SensorLink.IDLE, sensor.status.value.link)
        assertNull(sensor.status.value.batteryPercent?.takeIf { it !in 0..100 })
    }
}
