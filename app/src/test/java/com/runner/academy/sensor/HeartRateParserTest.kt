package com.runner.academy.sensor

import com.runner.academy.sensor.HeartRateMeasurement.Contact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HeartRateParserTest {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun parse(vararg values: Int) = HeartRateParser.parse(bytes(*values))

    @Test
    fun `uint8 value with no flags`() {
        val m = parse(0x00, 72)!!
        assertEquals(72, m.bpm)
        assertEquals(Contact.UNSUPPORTED, m.contact)
        assertNull(m.energyKj)
        assertEquals(emptyList<Int>(), m.rrMs)
    }

    @Test
    fun `uint8 value is unsigned`() {
        assertEquals(200, parse(0x00, 200)!!.bpm)
    }

    @Test
    fun `uint16 value is little endian and may exceed 255`() {
        assertEquals(0x012C, parse(0x01, 0x2C, 0x01)!!.bpm)
    }

    @Test
    fun `contact bits 00 and 01 mean the sensor does not report contact`() {
        assertEquals(Contact.UNSUPPORTED, parse(0x00, 70)!!.contact)
        assertEquals(Contact.UNSUPPORTED, parse(0x02, 70)!!.contact)
    }

    @Test
    fun `contact bits 10 mean supported but not detected`() {
        assertEquals(Contact.NOT_DETECTED, parse(0x04, 70)!!.contact)
    }

    @Test
    fun `contact bits 11 mean detected`() {
        assertEquals(Contact.DETECTED, parse(0x06, 70)!!.contact)
    }

    @Test
    fun `energy expended is read as uint16`() {
        assertEquals(0x0201, parse(0x08, 70, 0x01, 0x02)!!.energyKj)
    }

    @Test
    fun `energy expended shifts the rr offset`() {
        val m = parse(0x18, 70, 0x10, 0x00, 0x00, 0x04)!!
        assertEquals(16, m.energyKj)
        assertEquals(listOf(1000), m.rrMs)
    }

    @Test
    fun `rr after a uint16 value`() {
        val m = parse(0x11, 0x50, 0x00, 0x00, 0x02)!!
        assertEquals(80, m.bpm)
        assertEquals(listOf(500), m.rrMs)
    }

    @Test
    fun `rr of 1024 is 1000 ms and several intervals keep their order`() {
        val m = parse(0x10, 60, 0x00, 0x04, 0x00, 0x03, 0x01, 0x00)!!
        assertEquals(listOf(1000, 750, 1), m.rrMs)
    }

    @Test
    fun `rr flag with no intervals is an empty list`() {
        assertEquals(emptyList<Int>(), parse(0x10, 60)!!.rrMs)
    }

    @Test
    fun `everything at once`() {
        val m = parse(0x1F, 0xB4, 0x00, 0x2A, 0x00, 0x00, 0x04)!!
        assertEquals(180, m.bpm)
        assertEquals(Contact.DETECTED, m.contact)
        assertEquals(42, m.energyKj)
        assertEquals(listOf(1000), m.rrMs)
    }

    @Test
    fun `packets too short for the value or the energy are null`() {
        assertNull(parse())
        assertNull(parse(0x00))
        assertNull(parse(0x01, 0x50))
        assertNull(parse(0x08, 70, 0x01))
        assertNull(parse(0x18, 70, 0x01))
    }

    @Test
    fun `an incomplete last rr interval is dropped, the value is kept`() {
        val single = parse(0x10, 70, 0x00)!!
        assertEquals(70, single.bpm)
        assertEquals(emptyList<Int>(), single.rrMs)
        val tail = parse(0x16, 70, 0x00, 0x04, 0x00)!!
        assertEquals(70, tail.bpm)
        assertEquals(Contact.DETECTED, tail.contact)
        assertEquals(listOf(1000), tail.rrMs)
    }

    @Test
    fun `sample keeps a valid value when contact is detected or not reported`() {
        assertEquals(HeartRateSample(5L, 142), parse(0x06, 142)!!.toSample(5L))
        assertEquals(HeartRateSample(5L, 142), parse(0x00, 142)!!.toSample(5L))
    }

    @Test
    fun `sample has no value when contact is supported but not detected`() {
        assertEquals(HeartRateSample(7L, null), parse(0x04, 142)!!.toSample(7L))
    }

    @Test
    fun `sample has no value for zero or an implausible reading`() {
        assertNull(parse(0x06, 0)!!.toSample(0L).bpm)
        assertNull(parse(0x00, 24)!!.toSample(0L).bpm)
        assertNull(parse(0x01, 0x2C, 0x01)!!.toSample(0L).bpm)
        assertEquals(25, parse(0x00, 25)!!.toSample(0L).bpm)
        assertEquals(250, parse(0x00, 250)!!.toSample(0L).bpm)
    }
}
