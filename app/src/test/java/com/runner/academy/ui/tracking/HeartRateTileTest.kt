package com.runner.academy.ui.tracking

import com.runner.academy.sensor.PairedSensor
import com.runner.academy.sensor.SensorLink
import com.runner.academy.sensor.SensorType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeartRateTileTest {

    private val strap = PairedSensor(SensorType.HEART_RATE, "AA:BB", "HR Strap 10", 1L, null, null)

    @Test
    fun `without a sensor the tile opens Devices`() {
        for (link in SensorLink.entries) {
            val tile = HeartRateTile.of(null, link)
            assertEquals(HeartRateTile.NO_SENSOR, tile)
            assertTrue(tile.opensDevices)
        }
    }

    @Test
    fun `a paired sensor not connected opens Devices`() {
        for (link in SensorLink.entries - SensorLink.CONNECTED) {
            val tile = HeartRateTile.of(strap, link)
            assertEquals(HeartRateTile.DISCONNECTED, tile)
            assertTrue(tile.opensDevices)
        }
    }

    @Test
    fun `a connected sensor leaves the tile alone`() {
        val tile = HeartRateTile.of(strap, SensorLink.CONNECTED)

        assertEquals(HeartRateTile.CONNECTED, tile)
        assertFalse(tile.opensDevices)
    }
}
