package com.runner.academy.util.fit

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Writes synthetic FIT files for tests, from the public description of the protocol: a header,
 * definition and data messages (little or big endian), compressed timestamps, developer fields
 * and the file CRC. Real activity files stay out of the repository.
 */
class FitTestWriter(private val bigEndian: Boolean = false) {

    /** A field of a definition: its number, size in bytes and base type ([UINT8], [SINT32]…). */
    class Field(val num: Int, val size: Int, val baseType: Int)

    private val body = ByteArrayOutputStream()
    private val definitions = HashMap<Int, Pair<List<Field>, List<Int>>>()
    private val layouts = HashMap<Int, Int>()

    /** Defines [local] as [global] with [fields], and developer fields of [devSizes] bytes. */
    fun define(local: Int, global: Int, fields: List<Field>, devSizes: List<Int> = emptyList()): FitTestWriter {
        val dev = devSizes.isNotEmpty()
        body.write(0x40 or (if (dev) 0x20 else 0) or local)
        body.write(0)
        body.write(if (bigEndian) 1 else 0)
        body.write(ordered(2).putShort(global.toShort()).array())
        body.write(fields.size)
        fields.forEach {
            body.write(it.num)
            body.write(it.size)
            body.write(it.baseType)
        }
        if (dev) {
            body.write(devSizes.size)
            devSizes.forEachIndexed { i, size ->
                body.write(i)
                body.write(size)
                body.write(0)
            }
        }
        definitions[local] = fields to devSizes
        layouts.remove(local)
        return this
    }

    /** A data message of [local]: one value per field (null — the invalid value), developer bytes after. */
    fun data(local: Int, vararg values: Any?): FitTestWriter {
        body.write(local)
        writeValues(local, values)
        return this
    }

    /** A data message of [local] (0..3) with a compressed timestamp header of [offset] seconds. */
    fun compressed(local: Int, offset: Int, vararg values: Any?): FitTestWriter {
        body.write(0x80 or (local shl 5) or (offset and 0x1F))
        writeValues(local, values)
        return this
    }

    /** The file: a 14-byte header (12 with [shortHeader]), the messages and the CRC ([badCrc] spoils it). */
    fun bytes(shortHeader: Boolean = false, badCrc: Boolean = false): ByteArray {
        val data = body.toByteArray()
        val headerSize = if (shortHeader) 12 else 14
        val header = ByteBuffer.allocate(headerSize).order(ByteOrder.LITTLE_ENDIAN)
        header.put(headerSize.toByte())
        header.put(0x20)
        header.putShort(2132)
        header.putInt(data.size)
        header.put(".FIT".toByteArray(Charsets.US_ASCII))
        // A zero header CRC is allowed
        if (!shortHeader) header.putShort(0)
        val out = ByteArrayOutputStream()
        out.write(header.array())
        out.write(data)
        var crc = crc(out.toByteArray())
        if (badCrc) crc = crc xor 0x5555
        out.write(crc and 0xFF)
        out.write(crc shr 8 and 0xFF)
        return out.toByteArray()
    }

    // --- Activity messages, in the layouts a watch writes ---

    fun fileId(
        type: Int = 4,
        manufacturer: Int? = 1,
        productName: String? = null,
        timeCreated: Long? = null
    ) = message(
        FILE_ID_LOCAL, 0,
        listOf(Field(0, 1, ENUM), Field(1, 2, UINT16), Field(4, 4, UINT32), Field(8, 20, STRING)),
        type, manufacturer, timeCreated?.let(::fitTime), productName
    )

    fun deviceInfo(index: Int, manufacturer: Int? = null, productName: String? = null) = message(
        DEVICE_LOCAL, 23,
        listOf(Field(0, 1, UINT8), Field(2, 2, UINT16), Field(27, 20, STRING)),
        index, manufacturer, productName
    )

    fun sport(sport: Int?, subSport: Int? = 0, name: String? = null) = message(
        SPORT_LOCAL, 12,
        listOf(Field(0, 1, ENUM), Field(1, 1, ENUM), Field(3, 16, STRING)),
        sport, subSport, name
    )

    fun activity(type: Int) = message(ACTIVITY_LOCAL, 34, listOf(Field(2, 1, ENUM)), type)

    /** A track point at [time] (epoch ms), degrees, metres, beats and strides per minute. */
    fun record(
        time: Long?,
        lat: Double? = null,
        lon: Double? = null,
        altitude: Double? = null,
        enhancedAltitude: Double? = null,
        heartRate: Int? = null,
        cadence: Int? = null,
        fractionalCadence: Int? = null,
        distanceM: Double? = null,
        speed: Double? = null,
        enhancedSpeed: Double? = null
    ) = message(
        RECORD_LOCAL, 20,
        listOf(
            Field(253, 4, UINT32), Field(0, 4, SINT32), Field(1, 4, SINT32), Field(2, 2, UINT16),
            Field(78, 4, UINT32), Field(3, 1, UINT8), Field(4, 1, UINT8), Field(53, 1, UINT8),
            Field(5, 4, UINT32), Field(6, 2, UINT16), Field(73, 4, UINT32)
        ),
        time?.let(::fitTime), lat?.let(::semicircles), lon?.let(::semicircles),
        altitude?.let(::altitudeRaw), enhancedAltitude?.let(::altitudeRaw),
        heartRate, cadence, fractionalCadence,
        distanceM?.let { Math.round(it * 100) }, speed?.let { Math.round(it * 1000) },
        enhancedSpeed?.let { Math.round(it * 1000) }
    )

    /** A timer event: [type] 0 start, 1 stop, 4 stop_all; [trigger] 0 manual, 1 auto. */
    fun timer(time: Long, type: Int, trigger: Int? = null) = message(
        EVENT_LOCAL, 21,
        listOf(Field(253, 4, UINT32), Field(0, 1, ENUM), Field(1, 1, ENUM), Field(3, 4, UINT32)),
        fitTime(time), 0, type, trigger
    )

    fun lap(
        start: Long?,
        end: Long? = null,
        timerS: Double? = null,
        elapsedS: Double? = null,
        distanceM: Double? = null,
        avgHr: Int? = null,
        maxHr: Int? = null,
        avgCadence: Int? = null
    ) = message(
        LAP_LOCAL, 19,
        listOf(
            Field(253, 4, UINT32), Field(2, 4, UINT32), Field(7, 4, UINT32), Field(8, 4, UINT32),
            Field(9, 4, UINT32), Field(15, 1, UINT8), Field(16, 1, UINT8), Field(17, 1, UINT8)
        ),
        end?.let(::fitTime), start?.let(::fitTime), elapsedS?.let { Math.round(it * 1000) },
        timerS?.let { Math.round(it * 1000) }, distanceM?.let { Math.round(it * 100) },
        avgHr, maxHr, avgCadence
    )

    fun session(
        start: Long?,
        sport: Int? = 1,
        subSport: Int? = 0,
        timerS: Double? = null,
        distanceM: Double? = null,
        calories: Int? = null,
        avgHr: Int? = null,
        maxHr: Int? = null,
        avgCadence: Int? = null
    ) = message(
        SESSION_LOCAL, 18,
        listOf(
            Field(2, 4, UINT32), Field(5, 1, ENUM), Field(6, 1, ENUM), Field(8, 4, UINT32),
            Field(9, 4, UINT32), Field(11, 2, UINT16), Field(16, 1, UINT8), Field(17, 1, UINT8),
            Field(18, 1, UINT8)
        ),
        start?.let(::fitTime), sport, subSport, timerS?.let { Math.round(it * 1000) },
        distanceM?.let { Math.round(it * 100) }, calories, avgHr, maxHr, avgCadence
    )

    /** Defines [local] for [global] once (again when another layout took the slot), then the data. */
    private fun message(local: Int, global: Int, fields: List<Field>, vararg values: Any?): FitTestWriter {
        if (layouts[local] != global) {
            define(local, global, fields)
            layouts[local] = global
        }
        return data(local, *values)
    }

    private fun writeValues(local: Int, values: Array<out Any?>) {
        val (fields, devSizes) = definitions.getValue(local)
        require(values.size == fields.size) { "${fields.size} values expected, got ${values.size}" }
        fields.zip(values).forEach { (field, value) -> body.write(encode(field, value)) }
        // Developer data the decoder must step over
        devSizes.forEach { size -> repeat(size) { body.write(0xAB) } }
    }

    private fun encode(field: Field, value: Any?): ByteArray {
        if (field.baseType == STRING) {
            val text = (value as String?)?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
            return text.copyOf(field.size)
        }
        val buffer = ordered(field.size)
        val number = value as Number?
        when (field.baseType) {
            ENUM, UINT8 -> buffer.put((number?.toInt() ?: 0xFF).toByte())
            UINT8Z -> buffer.put((number?.toInt() ?: 0).toByte())
            SINT8 -> buffer.put((number?.toInt() ?: 0x7F).toByte())
            UINT16 -> buffer.putShort((number?.toInt() ?: 0xFFFF).toShort())
            SINT16 -> buffer.putShort((number?.toInt() ?: 0x7FFF).toShort())
            UINT32 -> buffer.putInt((number?.toLong() ?: 0xFFFFFFFFL).toInt())
            UINT32Z -> buffer.putInt((number?.toLong() ?: 0L).toInt())
            SINT32 -> buffer.putInt(number?.toInt() ?: 0x7FFFFFFF)
            FLOAT32 -> if (number == null) buffer.putInt(-1) else buffer.putFloat(number.toFloat())
            else -> error("No writer for base type ${field.baseType}")
        }
        return buffer.array()
    }

    private fun ordered(size: Int): ByteBuffer =
        ByteBuffer.allocate(size).order(if (bigEndian) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN)

    companion object {
        const val ENUM = 0x00
        const val SINT8 = 0x01
        const val UINT8 = 0x02
        const val SINT16 = 0x83
        const val UINT16 = 0x84
        const val SINT32 = 0x85
        const val UINT32 = 0x86
        const val STRING = 0x07
        const val FLOAT32 = 0x88
        const val UINT8Z = 0x0A
        const val UINT32Z = 0x8C

        private const val FILE_ID_LOCAL = 0
        private const val RECORD_LOCAL = 1
        private const val EVENT_LOCAL = 2
        private const val LAP_LOCAL = 3
        private const val SESSION_LOCAL = 4
        private const val DEVICE_LOCAL = 5
        private const val SPORT_LOCAL = 6
        private const val ACTIVITY_LOCAL = 7

        /** Seconds since 1989-12-31T00:00:00Z. */
        fun fitTime(epochMs: Long): Long = epochMs / 1000 - 631_065_600L

        fun semicircles(degrees: Double): Int = Math.round(degrees * (1L shl 31) / 180.0).toInt()

        private fun altitudeRaw(metres: Double): Long = Math.round((metres + 500) * 5)

        /** CRC-16 of the FIT protocol (nibble table, polynomial 0xA001). */
        fun crc(bytes: ByteArray): Int {
            val table = intArrayOf(
                0x0000, 0xCC01, 0xD801, 0x1400, 0xF001, 0x3C00, 0x2800, 0xE401,
                0xA001, 0x6C00, 0x7800, 0xB401, 0x5000, 0x9C01, 0x8801, 0x4400
            )
            var crc = 0
            for (b in bytes) {
                val byte = b.toInt() and 0xFF
                var tmp = table[crc and 0xF]
                crc = (crc shr 4) and 0x0FFF
                crc = crc xor tmp xor table[byte and 0xF]
                tmp = table[crc and 0xF]
                crc = (crc shr 4) and 0x0FFF
                crc = crc xor tmp xor table[byte shr 4 and 0xF]
            }
            return crc
        }
    }
}
