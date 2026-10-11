package com.runner.academy.sensor

import com.runner.academy.util.HeartRates
import kotlin.math.roundToInt

/**
 * One Heart Rate Measurement notification (characteristic `0x2A37`), as sent.
 *
 * @param bpm the raw value, not checked for plausibility (see [toSample]).
 * @param energyKj Energy Expended in kJ, null when the packet has none.
 * @param rrMs RR intervals in milliseconds, oldest first; empty when the packet has none.
 */
data class HeartRateMeasurement(
    val bpm: Int,
    val contact: Contact,
    val energyKj: Int?,
    val rrMs: List<Int>
) {

    /** The Sensor Contact Status bits. */
    enum class Contact {
        /** The sensor does not report skin contact (optical arm and wrist sensors). */
        UNSUPPORTED,
        NOT_DETECTED,
        DETECTED
    }

    /**
     * The reading as a sample at [elapsedNanos]: no value when the sensor says there is no skin
     * contact, or the value is not a plausible heart rate ([HeartRates.valid]). A sensor that
     * does not report contact is believed: there is no other signal.
     */
    fun toSample(elapsedNanos: Long): HeartRateSample = HeartRateSample(
        elapsedNanos,
        if (contact == Contact.NOT_DETECTED) null else HeartRates.valid(bpm)
    )
}

/** Decodes Heart Rate Measurement (`0x2A37`) packets of the Bluetooth Heart Rate Service. */
object HeartRateParser {

    private const val FLAG_UINT16 = 0x01
    private const val FLAG_CONTACT_DETECTED = 0x02
    private const val FLAG_CONTACT_SUPPORTED = 0x04
    private const val FLAG_ENERGY = 0x08
    private const val FLAG_RR = 0x10

    /** RR intervals are sent in 1/1024 s. */
    private const val RR_UNITS_PER_SECOND = 1024.0

    /** The measurement in [bytes], or null when the packet is shorter than its flags promise. */
    fun parse(bytes: ByteArray): HeartRateMeasurement? {
        if (bytes.isEmpty()) return null
        val flags = bytes.u8(0)
        var offset = 1

        val bpm: Int
        if (flags and FLAG_UINT16 != 0) {
            if (bytes.size < offset + 2) return null
            bpm = bytes.u16(offset)
            offset += 2
        } else {
            if (bytes.size < offset + 1) return null
            bpm = bytes.u8(offset)
            offset += 1
        }

        val contact = when {
            flags and FLAG_CONTACT_SUPPORTED == 0 -> HeartRateMeasurement.Contact.UNSUPPORTED
            flags and FLAG_CONTACT_DETECTED != 0 -> HeartRateMeasurement.Contact.DETECTED
            else -> HeartRateMeasurement.Contact.NOT_DETECTED
        }

        var energyKj: Int? = null
        if (flags and FLAG_ENERGY != 0) {
            if (bytes.size < offset + 2) return null
            energyKj = bytes.u16(offset)
            offset += 2
        }

        val rrMs = mutableListOf<Int>()
        if (flags and FLAG_RR != 0) {
            if ((bytes.size - offset) % 2 != 0) return null
            while (offset < bytes.size) {
                rrMs += (bytes.u16(offset) * 1000 / RR_UNITS_PER_SECOND).roundToInt()
                offset += 2
            }
        }

        return HeartRateMeasurement(bpm, contact, energyKj, rrMs)
    }

    private fun ByteArray.u8(at: Int): Int = this[at].toInt() and 0xFF

    private fun ByteArray.u16(at: Int): Int = u8(at) or (u8(at + 1) shl 8)
}
