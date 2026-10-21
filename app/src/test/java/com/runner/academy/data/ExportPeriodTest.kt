package com.runner.academy.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

class ExportPeriodTest {

    private val moscow = ZoneId.of("Europe/Moscow")
    private val newYork = ZoneId.of("America/New_York")

    private fun utcMidnight(date: LocalDate) = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    private fun ms(zone: ZoneId, y: Int, m: Int, d: Int, h: Int, min: Int = 0, s: Int = 0, nano: Int = 0) =
        ZonedDateTime.of(y, m, d, h, min, s, nano, zone).toInstant().toEpochMilli()

    @Test
    fun thePickedDays_areWholeLocalDays_fromTheFirstMidnightToTheLastMillisecond() {
        val period = ExportPeriod.ofPickerSelection(
            utcMidnight(LocalDate.of(2026, 10, 1)), utcMidnight(LocalDate.of(2026, 10, 21)), moscow
        )

        assertEquals(ms(moscow, 2026, 10, 1, 0), period.fromMs)
        assertEquals(ms(moscow, 2026, 10, 21, 23, 59, 59, 999_000_000), period.toMs)
    }

    @Test
    fun aZoneBehindUtc_keepsTheCalendarDaysPicked() {
        // UTC midnight of 21 Oct is still 20 Oct in New York: the day must not slip back
        val day = LocalDate.of(2026, 10, 21)

        val period = ExportPeriod.ofPickerSelection(utcMidnight(day), utcMidnight(day), newYork)

        assertEquals(ms(newYork, 2026, 10, 21, 0), period.fromMs)
        assertEquals(ms(newYork, 2026, 10, 22, 0) - 1, period.toMs)
    }

    @Test
    fun daysGivenBackwards_areTheSamePeriod() {
        val a = LocalDate.of(2026, 3, 1)
        val b = LocalDate.of(2026, 3, 31)

        assertEquals(ExportPeriod.ofDays(a, b, moscow), ExportPeriod.ofDays(b, a, moscow))
    }
}
