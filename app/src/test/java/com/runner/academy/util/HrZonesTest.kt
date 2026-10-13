package com.runner.academy.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class HrZonesTest {

    private val zone: ZoneId = ZoneOffset.UTC
    private val today = LocalDate.of(2026, 10, 13)

    private fun birth(year: Int, month: Int, day: Int): Long =
        LocalDate.of(year, month, day).atStartOfDay(zone).toInstant().toEpochMilli()

    // --- Boundaries ---

    @Test
    fun `HRmax 190 splits at 95, 114, 133, 152 and 171`() {
        assertArrayEquals(intArrayOf(95, 114, 133, 152, 171), HrZones(190).lowerBounds)
    }

    @Test
    fun `a lower bound is inclusive, the upper one is not`() {
        val zones = HrZones(190)

        assertEquals(4, zones.zoneOf(152))
        assertEquals(3, zones.zoneOf(151))
        assertEquals(1, zones.zoneOf(95))
        assertEquals(0, zones.zoneOf(94))
        assertEquals(5, zones.zoneOf(171))
    }

    @Test
    fun `at and above HRmax is zone 5`() {
        val zones = HrZones(190)

        assertEquals(5, zones.zoneOf(190))
        assertEquals(5, zones.zoneOf(200))
    }

    @Test
    fun `a half rounds up, exactly`() {
        // 185 × 0.5 = 92.5 and 185 × 0.7 = 129.5: no floating point drift below the half
        assertArrayEquals(intArrayOf(93, 111, 130, 148, 167), HrZones(185).lowerBounds)
    }

    @Test
    fun `a range ends one below the next zone, zone 5 at HRmax`() {
        val zones = HrZones(190)

        assertEquals(95..113, zones.range(1))
        assertEquals(152..170, zones.range(4))
        assertEquals(171..190, zones.range(5))
    }

    // --- HRmax ---

    @Test
    fun `no birth date and no manual value is no HRmax`() {
        assertNull(HrMax.resolve(0L, 0, today, zone))
    }

    @Test
    fun `by age it is 220 minus full years`() {
        assertEquals(HrMax(180, HrMaxSource.AUTO), HrMax.resolve(birth(1986, 10, 13), 0, today, zone))
    }

    @Test
    fun `the day before the birthday the age is still the old one`() {
        // Turns 40 tomorrow
        assertEquals(HrMax(181, HrMaxSource.AUTO), HrMax.resolve(birth(1986, 10, 14), 0, today, zone))
    }

    @Test
    fun `a manual value wins over the age`() {
        assertEquals(HrMax(195, HrMaxSource.MANUAL), HrMax.resolve(birth(1986, 10, 13), 195, today, zone))
        assertEquals(HrMax(195, HrMaxSource.MANUAL), HrMax.resolve(0L, 195, today, zone))
    }

    @Test
    fun `a manual value out of 100 to 230 is ignored`() {
        assertEquals(HrMax(180, HrMaxSource.AUTO), HrMax.resolve(birth(1986, 10, 13), 99, today, zone))
        assertNull(HrMax.resolve(0L, 231, today, zone))
        assertEquals(HrMax(100, HrMaxSource.MANUAL), HrMax.resolve(0L, 100, today, zone))
        assertEquals(HrMax(230, HrMaxSource.MANUAL), HrMax.resolve(0L, 230, today, zone))
    }

    @Test
    fun `an age outside 10 to 100 gives no HRmax`() {
        assertNull(HrMax.resolve(birth(2020, 1, 1), 0, today, zone))
        assertNull(HrMax.resolve(birth(1900, 1, 1), 0, today, zone))
        assertEquals(HrMax(210, HrMaxSource.AUTO), HrMax.resolve(birth(2016, 10, 13), 0, today, zone))
        assertEquals(HrMax(120, HrMaxSource.AUTO), HrMax.resolve(birth(1926, 10, 13), 0, today, zone))
    }

    @Test
    fun `a birth date before 1970 is a real one`() {
        assertEquals(HrMax(164, HrMaxSource.AUTO), HrMax.resolve(birth(1970, 1, 1) - 86_400_000L * 365, 0, LocalDate.of(2025, 1, 1), zone))
    }

    @Test
    fun `the age is read in the local time zone`() {
        // Midnight of 14 Oct in Moscow is still 13 Oct in UTC
        val moscow = ZoneId.of("Europe/Moscow")
        val birthMs = LocalDate.of(1986, 10, 14).atStartOfDay(moscow).toInstant().toEpochMilli()

        assertEquals(HrMax(181, HrMaxSource.AUTO), HrMax.resolve(birthMs, 0, today, moscow))
    }

    @Test
    fun `byAge ignores a manual value, the age of an automatic HRmax is known`() {
        assertEquals(HrMax(180, HrMaxSource.AUTO), HrMax.byAge(birth(1986, 10, 13), today, zone))
        assertNull(HrMax.byAge(0L, today, zone))
        assertEquals(40, HrMax(180, HrMaxSource.AUTO).ageYears)
        assertNull(HrMax(190, HrMaxSource.MANUAL).ageYears)
    }

    @Test
    fun `the zones of an HRmax are built on its value`() {
        assertEquals(HrZones(180), HrMax(180, HrMaxSource.AUTO).zones)
    }
}
