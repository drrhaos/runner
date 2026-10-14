package com.runner.academy.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class AgesTest {

    private val zone: ZoneId = ZoneOffset.UTC
    private val today = LocalDate.of(2026, 10, 13)

    private fun birth(year: Int, month: Int, day: Int, at: ZoneId = zone): Long =
        LocalDate.of(year, month, day).atStartOfDay(at).toInstant().toEpochMilli()

    @Test
    fun `no birth date is no age`() {
        assertNull(Ages.fullYears(0L, today, zone))
    }

    @Test
    fun `the age turns on the birthday, not a day before`() {
        assertEquals(40, Ages.fullYears(birth(1986, 10, 13), today, zone))
        assertEquals(39, Ages.fullYears(birth(1986, 10, 14), today, zone))
        assertEquals(40, Ages.fullYears(birth(1986, 10, 12), today, zone))
    }

    @Test
    fun `a leap day birthday turns on the first of March in a common year`() {
        assertEquals(18, Ages.fullYears(birth(2004, 2, 29), LocalDate.of(2023, 2, 28), zone))
        assertEquals(19, Ages.fullYears(birth(2004, 2, 29), LocalDate.of(2023, 3, 1), zone))
        assertEquals(20, Ages.fullYears(birth(2004, 2, 29), LocalDate.of(2024, 2, 29), zone))
    }

    @Test
    fun `a date before 1970 is real`() {
        assertEquals(60, Ages.fullYears(birth(1966, 1, 1), today, zone))
    }

    @Test
    fun `the stored midnight is read in the zone it was picked in`() {
        val moscow = ZoneId.of("Europe/Moscow")

        assertEquals(39, Ages.fullYears(birth(1986, 10, 14, moscow), today, moscow))
    }

    @Test
    fun `HRmax by age uses the same age`() {
        val birthMs = birth(1986, 10, 14)

        assertEquals(220 - Ages.fullYears(birthMs, today, zone)!!, HrMax.byAge(birthMs, today, zone)!!.bpm)
    }
}
