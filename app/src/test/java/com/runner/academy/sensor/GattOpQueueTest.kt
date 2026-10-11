package com.runner.academy.sensor

import com.runner.academy.sensor.GattUuids.BATTERY_LEVEL
import com.runner.academy.sensor.GattUuids.BATTERY_SERVICE
import com.runner.academy.sensor.GattUuids.HEART_RATE_MEASUREMENT
import com.runner.academy.sensor.GattUuids.HEART_RATE_SERVICE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GattOpQueueTest {

    private val scheduler = FakeLinkScheduler()
    private val started = mutableListOf<GattOp>()
    private val failed = mutableListOf<Pair<GattOp, Int?>>()
    private var startResult = true

    private val queue = GattOpQueue(
        scheduler,
        start = { op -> started += op; startResult },
        onFailed = { op, status -> failed += op to status }
    )

    private val discover = GattOp.Discover
    private val hrNotify = GattOp.EnableNotify(HEART_RATE_SERVICE, HEART_RATE_MEASUREMENT)
    private val battery = GattOp.Read(BATTERY_SERVICE, BATTERY_LEVEL)

    @Test
    fun `operations run one at a time in order`() {
        queue.add(discover)
        queue.add(hrNotify)
        queue.add(battery)
        assertEquals(listOf(discover), started)

        assertTrue(queue.finish(discover, GATT_SUCCESS))
        assertEquals(listOf(discover, hrNotify), started)
        assertTrue(queue.finish(hrNotify, GATT_SUCCESS))
        assertTrue(queue.finish(battery, GATT_SUCCESS))
        assertEquals(listOf(discover, hrNotify, battery), started)
        assertNull(queue.running)
        assertEquals(emptyList<Any>(), failed)
    }

    @Test
    fun `a callback for another operation does not finish the running one`() {
        queue.add(discover)
        queue.add(hrNotify)
        assertFalse(queue.finish(hrNotify, GATT_SUCCESS))
        assertEquals(discover, queue.running)
        assertEquals(listOf(discover), started)
    }

    @Test
    fun `an operation times out after 5 s and the next one starts`() {
        queue.add(discover)
        queue.add(battery)
        scheduler.advanceBy(GattOpQueue.OP_TIMEOUT_MS - 1)
        assertEquals(emptyList<Any>(), failed)

        scheduler.advanceBy(1)
        assertEquals(listOf(discover to null), failed)
        assertEquals(listOf(discover, battery), started)
        // A late callback of the timed-out operation is ignored
        assertFalse(queue.finish(discover, GATT_SUCCESS))
        assertEquals(battery, queue.running)
    }

    @Test
    fun `each operation gets its own timeout`() {
        queue.add(discover)
        queue.add(hrNotify)
        scheduler.advanceBy(4_000)
        queue.finish(discover, GATT_SUCCESS)
        scheduler.advanceBy(4_000)
        assertEquals(emptyList<Any>(), failed)
        scheduler.advanceBy(1_000)
        assertEquals(listOf(hrNotify to null), failed)
    }

    @Test
    fun `an error status fails the operation`() {
        queue.add(hrNotify)
        assertFalse(queue.finish(hrNotify, 133))
        assertEquals(listOf(hrNotify to 133), failed)
        assertNull(queue.running)
        assertEquals(0, scheduler.pendingCount)
    }

    @Test
    fun `an operation that does not start fails at once`() {
        startResult = false
        queue.add(discover)
        queue.add(battery)
        assertEquals(listOf(discover to null, battery to null), failed)
        assertNull(queue.running)
        assertEquals(0, scheduler.pendingCount)
    }

    @Test
    fun `clear drops everything and stops the timer`() {
        queue.add(discover)
        queue.add(hrNotify)
        queue.clear()
        assertNull(queue.running)
        assertEquals(0, scheduler.pendingCount)
        assertFalse(queue.finish(discover, GATT_SUCCESS))
        scheduler.advanceBy(10_000)
        assertEquals(emptyList<Any>(), failed)

        queue.add(battery)
        assertEquals(listOf(discover, battery), started)
    }

    private companion object {
        const val GATT_SUCCESS = 0
    }
}
