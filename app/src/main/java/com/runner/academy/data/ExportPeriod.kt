package com.runner.academy.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * The workouts an export takes: those started in [fromMs]..[toMs] (inclusive, `date` ms).
 * Whole days of the phone's time zone, as the list groups them.
 */
data class ExportPeriod(val fromMs: Long, val toMs: Long) {

    companion object {
        /** From the first day to the end of the last, both in [zone]. */
        fun ofDays(first: LocalDate, last: LocalDate, zone: ZoneId): ExportPeriod {
            val (from, to) = if (first <= last) first to last else last to first
            return ExportPeriod(
                fromMs = from.atStartOfDay(zone).toInstant().toEpochMilli(),
                toMs = to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
            )
        }

        /**
         * A date range picker's selection: each day is the UTC midnight of that calendar day,
         * whatever the zone, so it is read back as a date in UTC and then laid on [zone].
         */
        fun ofPickerSelection(firstUtcMs: Long, lastUtcMs: Long, zone: ZoneId): ExportPeriod =
            ofDays(utcDate(firstUtcMs), utcDate(lastUtcMs), zone)

        private fun utcDate(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()
    }
}
