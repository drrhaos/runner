package com.runner.academy.sensor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorPermissionPolicyTest {

    private val scan = "android.permission.BLUETOOTH_SCAN"
    private val connect = "android.permission.BLUETOOTH_CONNECT"
    private val fine = "android.permission.ACCESS_FINE_LOCATION"

    @Test
    fun `31 and up ask for nearby devices at once`() {
        assertEquals(listOf(scan, connect), SensorPermissionPolicy.scanPermissions(31))
        assertEquals(listOf(scan, connect), SensorPermissionPolicy.scanPermissions(36))
        assertEquals(listOf(connect), SensorPermissionPolicy.connectPermissions(31))
    }

    @Test
    fun `30 and below scan with the precise location and connect with nothing`() {
        assertEquals(listOf(fine), SensorPermissionPolicy.scanPermissions(30))
        assertEquals(listOf(fine), SensorPermissionPolicy.scanPermissions(26))
        assertEquals(emptyList<String>(), SensorPermissionPolicy.connectPermissions(30))
    }

    @Test
    fun `30 with location switched off cannot scan`() {
        assertEquals(ScanBlocker.LOCATION_OFF, SensorPermissionPolicy.canScan(30, setOf(fine), locationOn = false))
    }

    @Test
    fun `30 without the precise location cannot scan`() {
        assertEquals(ScanBlocker.NO_PERMISSION, SensorPermissionPolicy.canScan(30, emptySet(), locationOn = true))
    }

    @Test
    fun `30 with location granted and on can scan`() {
        assertNull(SensorPermissionPolicy.canScan(30, setOf(fine), locationOn = true))
    }

    @Test
    fun `31 scans without location, but needs both nearby permissions`() {
        assertNull(SensorPermissionPolicy.canScan(31, setOf(scan, connect), locationOn = false))
        assertEquals(ScanBlocker.NO_PERMISSION, SensorPermissionPolicy.canScan(31, setOf(scan), locationOn = true))
        assertEquals(ScanBlocker.NO_PERMISSION, SensorPermissionPolicy.canScan(31, setOf(fine), locationOn = true))
    }

    @Test
    fun `connecting needs connect from 31 only`() {
        assertTrue(SensorPermissionPolicy.canConnect(30, emptySet()))
        assertFalse(SensorPermissionPolicy.canConnect(31, emptySet()))
        assertTrue(SensorPermissionPolicy.canConnect(31, setOf(connect)))
    }
}
