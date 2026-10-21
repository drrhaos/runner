package com.runner.academy.sensor

import org.junit.Assert.assertEquals
import org.junit.Test

class ForegroundTypesTest {

    private val location = 0x08
    private val connectedDevice = 0x10

    @Test
    fun `33 is location only, even with a sensor`() {
        assertEquals(location, ForegroundTypes.of(33, sensorPaired = true, connectGranted = true))
        assertEquals(location, ForegroundTypes.of(26, sensorPaired = true, connectGranted = true))
    }

    @Test
    fun `34 with a paired sensor and connect granted adds connected device`() {
        assertEquals(location or connectedDevice, ForegroundTypes.of(34, sensorPaired = true, connectGranted = true))
        assertEquals(location or connectedDevice, ForegroundTypes.of(36, sensorPaired = true, connectGranted = true))
    }

    @Test
    fun `34 without connect is location only`() {
        assertEquals(location, ForegroundTypes.of(34, sensorPaired = true, connectGranted = false))
    }

    @Test
    fun `34 without a paired sensor is location only`() {
        assertEquals(location, ForegroundTypes.of(34, sensorPaired = false, connectGranted = true))
    }
}
