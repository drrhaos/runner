package com.runner.academy.util.fit

import com.runner.academy.util.fit.FitTestWriter.Companion.FLOAT32
import com.runner.academy.util.fit.FitTestWriter.Companion.SINT32
import com.runner.academy.util.fit.FitTestWriter.Companion.STRING
import com.runner.academy.util.fit.FitTestWriter.Companion.UINT16
import com.runner.academy.util.fit.FitTestWriter.Companion.UINT32
import com.runner.academy.util.fit.FitTestWriter.Companion.UINT8
import com.runner.academy.util.fit.FitTestWriter.Field
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FitDecoderTest {

    private fun decode(bytes: ByteArray, globals: Set<Int>? = null) =
        FitDecoder.decode(bytes.inputStream(), globals)

    private val start = 1_700_000_000_000L

    @Test
    fun `reads data messages by their definitions`() {
        val bytes = FitTestWriter()
            .define(0, 20, listOf(Field(253, 4, UINT32), Field(0, 4, SINT32), Field(3, 1, UINT8)))
            .data(0, 1000L, -123456, 150)
            .bytes()

        val decoded = decode(bytes)

        assertTrue(decoded.intact)
        val record = decoded.messages.single()
        assertEquals(20, record.global)
        assertEquals(1000L, record.long(253))
        assertEquals(-123456L, record.long(0))
        assertEquals(150, record.int(3))
    }

    @Test
    fun `an invalid value is no field`() {
        val bytes = FitTestWriter()
            .define(0, 20, listOf(Field(3, 1, UINT8), Field(0, 4, SINT32), Field(2, 2, UINT16), Field(7, 4, FLOAT32)))
            .data(0, null, null, null, null)
            .bytes()

        val record = decode(bytes).messages.single()

        assertNull(record.int(3))
        assertNull(record.long(0))
        assertNull(record.long(2))
        assertNull(record.double(7))
    }

    @Test
    fun `reads strings and floats`() {
        val bytes = FitTestWriter()
            .define(0, 23, listOf(Field(27, 12, STRING), Field(5, 4, FLOAT32)))
            .data(0, "Run Watch", 1.5)
            .bytes()

        val message = decode(bytes).messages.single()

        assertEquals("Run Watch", message.string(27))
        assertEquals(1.5, message.double(5)!!, 1e-9)
    }

    @Test
    fun `reads big-endian definitions`() {
        val bytes = FitTestWriter(bigEndian = true)
            .define(0, 20, listOf(Field(253, 4, UINT32), Field(2, 2, UINT16)))
            .data(0, 70_000L, 3000)
            .bytes()

        val record = decode(bytes).messages.single()

        assertEquals(20, record.global)
        assertEquals(70_000L, record.long(253))
        assertEquals(3000L, record.long(2))
    }

    @Test
    fun `a short header reads the same`() {
        val bytes = FitTestWriter().fileId().bytes(shortHeader = true)

        val decoded = decode(bytes)

        assertTrue(decoded.intact)
        assertEquals(4, decoded.messages.single().int(0))
    }

    @Test
    fun `a redefined local slot reads with its new definition`() {
        val bytes = FitTestWriter()
            .define(0, 20, listOf(Field(3, 1, UINT8)))
            .data(0, 140)
            .define(0, 19, listOf(Field(9, 4, UINT32)))
            .data(0, 100_000L)
            .bytes()

        val messages = decode(bytes).messages

        assertEquals(listOf(20, 19), messages.map { it.global })
        assertEquals(140, messages[0].int(3))
        assertEquals(100_000L, messages[1].long(9))
    }

    @Test
    fun `developer fields are stepped over`() {
        val bytes = FitTestWriter()
            .define(0, 20, listOf(Field(3, 1, UINT8)), devSizes = listOf(4, 2))
            .data(0, 140)
            .data(0, 141)
            .bytes()

        val decoded = decode(bytes)

        assertTrue(decoded.intact)
        assertEquals(listOf(140, 141), decoded.messages.map { it.int(3) })
    }

    @Test
    fun `a field shorter than its type is skipped, the rest still reads`() {
        // Some watches declare a uint32 with one byte
        val patched = FitTestWriter()
            .define(0, 20, listOf(Field(5, 1, UINT8), Field(3, 1, UINT8)))
            .data(0, 7, 150)
            .bytes()
            .also { file ->
                // The base type of the first field becomes uint32 (0x86) though its size stays 1
                val at = indexOf(file, byteArrayOf(5, 1, UINT8.toByte()))
                file[at + 2] = UINT32.toByte()
            }

        val record = decode(patched).messages.single()

        assertNull(record.long(5))
        assertEquals(150, record.int(3))
    }

    @Test
    fun `compressed timestamps count from the last full one`() {
        val base = FitTestWriter.fitTime(start)
        val writer = FitTestWriter()
            .define(0, 20, listOf(Field(253, 4, UINT32), Field(3, 1, UINT8)))
            .data(0, base, 140)
            .define(1, 20, listOf(Field(3, 1, UINT8)))
        val low = (base and 0x1F).toInt()
        writer.compressed(1, (low + 3) and 0x1F, 141)
        // Past the 32-second roll-over
        writer.compressed(1, (low + 3 + 30) and 0x1F, 142)

        val messages = decode(writer.bytes()).messages

        assertEquals(listOf(base, base + 3, base + 33), messages.map { it.long(253) })
        assertEquals(listOf(140, 141, 142), messages.map { it.int(3) })
    }

    @Test
    fun `a compressed timestamp before any full one gives no time`() {
        val writer = FitTestWriter().define(0, 20, listOf(Field(3, 1, UINT8)))
        writer.compressed(0, 5, 140)

        val record = decode(writer.bytes()).messages.single()

        assertNull(record.long(253))
        assertEquals(140, record.int(3))
    }

    @Test
    fun `chained files read one after another, each with its own definitions`() {
        val first = FitTestWriter()
            .define(0, 20, listOf(Field(3, 1, UINT8)))
            .data(0, 140)
            .bytes()
        val second = FitTestWriter()
            .define(0, 19, listOf(Field(9, 4, UINT32)))
            .data(0, 5000L)
            .bytes()

        val decoded = decode(first + second + byteArrayOf(1, 2, 3))

        assertTrue(decoded.intact)
        assertEquals(listOf(20, 19), decoded.messages.map { it.global })
    }

    @Test
    fun `a chained file after a bad CRC still reads`() {
        val first = FitTestWriter().fileId().bytes(badCrc = true)
        val second = FitTestWriter().fileId().bytes()

        val decoded = decode(first + second)

        assertFalse(decoded.intact)
        assertEquals(2, decoded.messages.size)
    }

    @Test
    fun `a bad CRC keeps the messages but the file is not intact`() {
        val bytes = FitTestWriter().fileId().bytes(badCrc = true)

        val decoded = decode(bytes)

        assertFalse(decoded.intact)
        assertEquals(1, decoded.messages.size)
    }

    @Test
    fun `a cut file keeps the messages read before the cut`() {
        val bytes = FitTestWriter()
            .fileId()
            .record(start, 55.0, 37.0)
            .record(start + 1000, 55.0001, 37.0)
            .bytes()

        val decoded = decode(bytes.copyOf(bytes.size - 7))

        assertFalse(decoded.intact)
        assertEquals(listOf(0, 20), decoded.messages.map { it.global })
    }

    @Test
    fun `data of an undefined slot stops the reading`() {
        val bytes = FitTestWriter().fileId().bytes()
        // A data message header for slot 9, never defined, in place of the CRC
        val broken = bytes.copyOf(bytes.size - 2) + byteArrayOf(9, 0, 0)

        val decoded = decode(broken)

        assertFalse(decoded.intact)
        assertEquals(1, decoded.messages.size)
    }

    @Test
    fun `only the asked messages are kept`() {
        val bytes = FitTestWriter()
            .fileId()
            .record(start, 55.0, 37.0)
            .bytes()

        val decoded = decode(bytes, globals = setOf(20))

        assertEquals(listOf(20), decoded.messages.map { it.global })
    }

    @Test(expected = IllegalArgumentException::class)
    fun `not a FIT file`() {
        decode("<?xml version=\"1.0\"?><gpx/>".toByteArray())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a header size below twelve is no FIT file`() {
        decode(byteArrayOf(0, 1, 2))
    }

    @Test
    fun `isFit tells a FIT header`() {
        assertTrue(FitDecoder.isFit(FitTestWriter().fileId().bytes()))
        assertFalse(FitDecoder.isFit("<?xml version=\"1.0\"?><gpx></gpx>".toByteArray()))
        assertFalse(FitDecoder.isFit(byteArrayOf(14, 0, 0)))
    }

    @Test
    fun `isFit on a stream leaves it at the start`() {
        val stream = java.io.BufferedInputStream(FitTestWriter().fileId().bytes().inputStream())

        assertTrue(FitDecoder.isFit(stream))
        assertTrue(FitDecoder.decode(stream).intact)
        assertFalse(FitDecoder.isFit(java.io.BufferedInputStream("<gpx/>".byteInputStream())))
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int =
        (0..haystack.size - needle.size).first { i -> needle.indices.all { haystack[i + it] == needle[it] } }
}
