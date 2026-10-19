package com.runner.academy.util.fit

import java.io.BufferedInputStream
import java.io.EOFException
import java.io.InputStream

/**
 * One data message of a FIT file: its global message number and its fields by number. A field
 * holding the invalid value of its type is left out; integers are [Long], floats [Double],
 * strings [String]. Arrays and fields whose size does not fit their type are not kept.
 */
class FitMessage(val global: Int, val fields: Map<Int, Any>) {

    fun long(num: Int): Long? = fields[num] as? Long

    fun int(num: Int): Int? = long(num)?.toInt()

    /** An integer or float field as a double. */
    fun double(num: Int): Double? = when (val value = fields[num]) {
        is Long -> value.toDouble()
        is Double -> value
        else -> null
    }

    fun string(num: Int): String? = fields[num] as? String
}

/**
 * The messages of a FIT file. Not [intact]: a CRC did not match, or the file ended (or broke)
 * before its declared size — what was read before stays.
 */
class FitDecoding(val messages: List<FitMessage>, val intact: Boolean)

/**
 * Reads a FIT file (an independent implementation of the public FIT protocol) into its data
 * messages; it knows nothing of workouts ([FitImporter] does). Handles both header sizes, little
 * and big endian definitions, redefined local slots, compressed timestamps and chained files;
 * unknown messages, fields and developer fields are stepped over by their size.
 */
object FitDecoder {

    /** The common field every message may carry: seconds since 1989-12-31T00:00:00Z. */
    const val TIMESTAMP = 253

    private const val MIN_HEADER = 12

    /** [head] (the first bytes of a file) starts a FIT file: a header size and `.FIT`. */
    fun isFit(head: ByteArray): Boolean =
        head.size >= MIN_HEADER && (head[0].toInt() and 0xFF) >= MIN_HEADER &&
            head[8] == '.'.code.toByte() && head[9] == 'F'.code.toByte() &&
            head[10] == 'I'.code.toByte() && head[11] == 'T'.code.toByte()

    /**
     * The data messages of [input], only those of [globals] when given. Throws
     * IllegalArgumentException when it does not start as a FIT file; damage after that only
     * makes the result not [FitDecoding.intact].
     */
    fun decode(input: InputStream, globals: Set<Int>? = null): FitDecoding {
        val reader = Reader(if (input is BufferedInputStream) input else BufferedInputStream(input))
        val messages = ArrayList<FitMessage>()
        var first = true
        var intact = true
        while (true) {
            val outcome = decodeFile(reader, first, globals, messages)
            first = false
            when (outcome) {
                FileOutcome.INTACT -> Unit
                FileOutcome.BAD_CRC -> intact = false
                FileOutcome.END -> return FitDecoding(messages, intact)
                FileOutcome.BROKEN -> return FitDecoding(messages, intact = false)
            }
        }
    }

    private enum class FileOutcome {
        /** Read to its CRC, which matched: another chained file may follow. */
        INTACT,

        /** Read to its CRC, which did not match: another chained file may still follow. */
        BAD_CRC,

        /** No further file: the end of the input, or bytes after the last CRC that are none. */
        END,

        /** Ended or broke before its declared size. */
        BROKEN
    }

    /** A local message slot: the layout its data messages have. */
    private class Definition(
        val global: Int,
        val bigEndian: Boolean,
        val fields: List<FieldDef>,
        val devBytes: Int
    )

    private class FieldDef(val num: Int, val size: Int, val baseType: Int)

    private fun decodeFile(
        reader: Reader,
        first: Boolean,
        globals: Set<Int>?,
        messages: MutableList<FitMessage>
    ): FileOutcome {
        reader.startFile()
        val headerSize = reader.readOrEnd()
        if (headerSize < 0) {
            if (first) throw IllegalArgumentException("Empty file")
            return FileOutcome.END
        }
        val header = try {
            if (headerSize < MIN_HEADER) {
                null
            } else {
                ByteArray(headerSize).also {
                    it[0] = headerSize.toByte()
                    reader.readFully(it, 1, headerSize - 1)
                }
            }
        } catch (_: EOFException) {
            null
        }
        if (header == null || !isFit(header)) {
            if (first) throw IllegalArgumentException("Not a FIT file")
            // Bytes after the last file: not another one
            return FileOutcome.END
        }
        val dataSize = (header[4].toLong() and 0xFF) or
            ((header[5].toLong() and 0xFF) shl 8) or
            ((header[6].toLong() and 0xFF) shl 16) or
            ((header[7].toLong() and 0xFF) shl 24)
        val dataEnd = headerSize + dataSize

        val definitions = arrayOfNulls<Definition>(16)
        var lastTimestamp: Long? = null
        try {
            while (reader.position < dataEnd) {
                val recordHeader = reader.read()
                if (recordHeader and 0x80 != 0) {
                    // Compressed timestamp header: slots 0..3, a 5-bit offset in seconds
                    val definition = definitions[recordHeader shr 5 and 0x03] ?: return FileOutcome.BROKEN
                    val fields = readFields(reader, definition)
                    lastTimestamp?.let { last ->
                        val offset = (recordHeader and 0x1F).toLong()
                        var time = (last and 0x1FL.inv()) + offset
                        if (offset < (last and 0x1F)) time += 0x20
                        lastTimestamp = time
                        fields[TIMESTAMP] = time
                    }
                    keep(definition, fields, globals, messages)
                } else if (recordHeader and 0x40 != 0) {
                    definitions[recordHeader and 0x0F] = readDefinition(reader, recordHeader and 0x20 != 0)
                } else {
                    val definition = definitions[recordHeader and 0x0F] ?: return FileOutcome.BROKEN
                    val fields = readFields(reader, definition)
                    (fields[TIMESTAMP] as? Long)?.let { lastTimestamp = it }
                    keep(definition, fields, globals, messages)
                }
            }
            // A message ran past the declared end: the CRC would be read from the wrong place
            if (reader.position != dataEnd) return FileOutcome.BROKEN
            val computed = reader.crc
            val stored = reader.read() or (reader.read() shl 8)
            return if (stored == computed) FileOutcome.INTACT else FileOutcome.BAD_CRC
        } catch (_: EOFException) {
            return FileOutcome.BROKEN
        }
    }

    private fun keep(
        definition: Definition,
        fields: Map<Int, Any>,
        globals: Set<Int>?,
        messages: MutableList<FitMessage>
    ) {
        if (globals == null || definition.global in globals) messages += FitMessage(definition.global, fields)
    }

    private fun readDefinition(reader: Reader, developer: Boolean): Definition {
        reader.read() // reserved
        val bigEndian = reader.read() == 1
        val b0 = reader.read()
        val b1 = reader.read()
        val global = if (bigEndian) (b0 shl 8) or b1 else (b1 shl 8) or b0
        val fields = List(reader.read()) {
            FieldDef(num = reader.read(), size = reader.read(), baseType = reader.read())
        }
        var devBytes = 0
        if (developer) {
            repeat(reader.read()) {
                reader.read() // field number
                devBytes += reader.read()
                reader.read() // developer data index
            }
        }
        return Definition(global, bigEndian, fields, devBytes)
    }

    private fun readFields(reader: Reader, definition: Definition): HashMap<Int, Any> {
        val fields = HashMap<Int, Any>()
        for (field in definition.fields) {
            val bytes = ByteArray(field.size)
            reader.readFully(bytes, 0, field.size)
            valueOf(bytes, field.baseType, definition.bigEndian)?.let { fields[field.num] = it }
        }
        reader.skip(definition.devBytes)
        return fields
    }

    /** The value of one field, null when invalid, an array, or a size its type cannot have. */
    private fun valueOf(bytes: ByteArray, baseType: Int, bigEndian: Boolean): Any? {
        val type = baseType and 0x1F
        if (type == STRING) {
            val end = bytes.indexOf(0).let { if (it < 0) bytes.size else it }
            return if (end == 0) null else String(bytes, 0, end, Charsets.UTF_8)
        }
        val size = SIZES.getOrNull(type) ?: return null
        if (bytes.size != size) return null
        var raw = 0L
        for (i in 0 until size) {
            val b = bytes[if (bigEndian) i else size - 1 - i].toLong() and 0xFF
            raw = (raw shl 8) or b
        }
        val allOnes = if (size == 8) -1L else (1L shl (size * 8)) - 1
        val signedMax = if (size == 8) Long.MAX_VALUE else (1L shl (size * 8 - 1)) - 1
        return when (type) {
            ENUM, UINT8, UINT16, UINT32, BYTE, UINT64 -> raw.takeIf { it != allOnes }
            UINT8Z, UINT16Z, UINT32Z, UINT64Z -> raw.takeIf { it != 0L }
            SINT8, SINT16, SINT32, SINT64 -> if (raw == signedMax) {
                null
            } else {
                // Sign-extend from the field's width
                val shift = 64 - size * 8
                (raw shl shift) shr shift
            }
            FLOAT32 -> Float.fromBits(raw.toInt()).toDouble().takeIf { it.isFinite() }
            FLOAT64 -> Double.fromBits(raw).takeIf { it.isFinite() }
            else -> null
        }
    }

    // Base type numbers (the low 5 bits of the base type byte) and their sizes
    private const val ENUM = 0x00
    private const val SINT8 = 0x01
    private const val UINT8 = 0x02
    private const val SINT16 = 0x03
    private const val UINT16 = 0x04
    private const val SINT32 = 0x05
    private const val UINT32 = 0x06
    private const val STRING = 0x07
    private const val FLOAT32 = 0x08
    private const val FLOAT64 = 0x09
    private const val UINT8Z = 0x0A
    private const val UINT16Z = 0x0B
    private const val UINT32Z = 0x0C
    private const val BYTE = 0x0D
    private const val SINT64 = 0x0E
    private const val UINT64 = 0x0F
    private const val UINT64Z = 0x10
    private val SIZES = listOf(1, 1, 1, 2, 2, 4, 4, 1, 4, 8, 1, 2, 4, 1, 8, 8, 8)

    private val CRC_TABLE = intArrayOf(
        0x0000, 0xCC01, 0xD801, 0x1400, 0xF001, 0x3C00, 0x2800, 0xE401,
        0xA001, 0x6C00, 0x7800, 0xB401, 0x5000, 0x9C01, 0x8801, 0x4400
    )

    /** Reads bytes one file at a time, counting them and their CRC-16 from the file start. */
    private class Reader(private val input: InputStream) {
        var position = 0L
            private set
        var crc = 0
            private set

        fun startFile() {
            position = 0L
            crc = 0
        }

        /** The next byte, or -1 at the end of the input. */
        fun readOrEnd(): Int {
            val b = input.read()
            if (b >= 0) count(b)
            return b
        }

        fun read(): Int {
            val b = input.read()
            if (b < 0) throw EOFException()
            count(b)
            return b
        }

        fun readFully(bytes: ByteArray, offset: Int, length: Int) {
            for (i in offset until offset + length) bytes[i] = read().toByte()
        }

        fun skip(count: Int) = repeat(count) { read() }

        private fun count(b: Int) {
            position++
            var tmp = CRC_TABLE[crc and 0xF]
            crc = (crc shr 4) and 0x0FFF
            crc = crc xor tmp xor CRC_TABLE[b and 0xF]
            tmp = CRC_TABLE[crc and 0xF]
            crc = (crc shr 4) and 0x0FFF
            crc = crc xor tmp xor CRC_TABLE[b shr 4 and 0xF]
        }
    }
}
