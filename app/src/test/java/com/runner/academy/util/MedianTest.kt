package com.runner.academy.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MedianTest {

    @Test
    fun `odd count takes the middle value`() {
        assertEquals(3.0, doubleArrayOf(5.0, 1.0, 3.0).median()!!, 0.0)
        assertEquals(3.0, listOf(5.0, 1.0, 3.0).median()!!, 0.0)
    }

    @Test
    fun `even count takes the mean of the middle two`() {
        assertEquals(2.5, doubleArrayOf(4.0, 1.0, 2.0, 3.0).median()!!, 0.0)
    }

    @Test
    fun `no values has no median`() {
        assertNull(DoubleArray(0).median())
        assertNull(emptyList<Double>().median())
    }

    @Test
    fun `the values are left as they are`() {
        val values = doubleArrayOf(3.0, 1.0, 2.0)
        values.median()
        assertArrayEquals(doubleArrayOf(3.0, 1.0, 2.0), values, 0.0)
    }
}
