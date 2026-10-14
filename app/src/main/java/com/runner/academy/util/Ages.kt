package com.runner.academy.util

import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId

/** The user's age: one rule for the settings' age row and the HRmax by age. */
object Ages {

    /**
     * Full years on [today] of a birth date stored as [birthDateMs] (the local midnight the date
     * picker writes, read in [zone]); the age turns on the birthday itself. Null when not set
     * (0; a date before 1970 is negative and real).
     */
    fun fullYears(birthDateMs: Long, today: LocalDate, zone: ZoneId): Int? {
        if (birthDateMs == 0L) return null
        val birthday = Instant.ofEpochMilli(birthDateMs).atZone(zone).toLocalDate()
        return Period.between(birthday, today).years
    }
}
