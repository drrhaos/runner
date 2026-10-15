package com.runner.academy.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class CaloriesTest {

    private val man = CalorieProfile(weightKg = 70f, sex = CalorieProfile.Sex.MALE, ageYears = 35)
    private val woman = CalorieProfile(weightKg = 60f, sex = CalorieProfile.Sex.FEMALE, ageYears = 30)
    private val hour = 3_600_000L
    private val min45 = 2_700_000L

    @Test
    fun `without heart rate ACSM running gives gross oxygen over distance and moving time`() {
        // (0.2 × 10000 + 3.5 × 45) ml/kg × 70 kg / 1000 × 5 kcal/l = 755.1
        assertEquals(755, Calories.estimate(man, distanceM = 10_000f, movingMs = min45, climbM = null, avgHeartRate = null))
    }

    @Test
    fun `the climb adds the vertical term of ACSM`() {
        // + 0.9 × 100 m × 70 / 1000 × 5 = 31.5 → 786.6
        assertEquals(787, Calories.estimate(man, 10_000f, min45, climbM = 100f, avgHeartRate = null))
    }

    @Test
    fun `with heart rate and age Keytel for men over moving time`() {
        // (−55.0969 + 0.6309 × 150 + 0.1988 × 70 + 0.2017 × 35) / 4.184 × 60 = 867.7
        assertEquals(868, Calories.estimate(man, 10_000f, hour, climbM = 100f, avgHeartRate = 150))
    }

    @Test
    fun `Keytel for women`() {
        // (−20.4022 + 0.4472 × 160 − 0.1263 × 60 + 0.074 × 30) / 4.184 × 60 = 656.7
        assertEquals(657, Calories.estimate(woman, 10_000f, hour, climbM = null, avgHeartRate = 160))
    }

    @Test
    fun `without age the heart rate is not used`() {
        val noAge = man.copy(ageYears = null)
        assertEquals(755, Calories.estimate(noAge, 10_000f, min45, climbM = null, avgHeartRate = 150))
    }

    @Test
    fun `a heart rate too low for Keytel gives zero, never negative`() {
        assertEquals(0, Calories.estimate(man, 0f, hour, climbM = null, avgHeartRate = 40))
    }

    @Test
    fun `no moving time gives zero`() {
        assertEquals(0, Calories.estimate(man, 0f, 0L, climbM = null, avgHeartRate = null))
        assertEquals(0, Calories.estimate(man, 500f, 0L, climbM = null, avgHeartRate = 150))
    }

    @Test
    fun `a negative climb is ignored`() {
        assertEquals(755, Calories.estimate(man, 10_000f, min45, climbM = -50f, avgHeartRate = null))
    }

    @Test
    fun `more weight burns more`() {
        val heavy = man.copy(weightKg = 90f)
        assertTrue(Calories.estimate(heavy, 10_000f, hour, null, null) > Calories.estimate(man, 10_000f, hour, null, null))
    }

    @Test
    fun `the profile takes the age from the birth date and drops an implausible one`() {
        val zone = ZoneId.of("UTC")
        val today = LocalDate.of(2026, 10, 15)
        val born1990 = LocalDate.of(1990, 10, 15).atStartOfDay(zone).toInstant().toEpochMilli()
        assertEquals(36, CalorieProfile.of(70f, "male", born1990, today, zone).ageYears)
        assertEquals(null, CalorieProfile.of(70f, "male", 0L, today, zone).ageYears)
        val born2024 = LocalDate.of(2024, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli()
        assertEquals(null, CalorieProfile.of(70f, "male", born2024, today, zone).ageYears)
        assertEquals(CalorieProfile.Sex.FEMALE, CalorieProfile.of(70f, "female", 0L, today, zone).sex)
        assertEquals(CalorieProfile.Sex.MALE, CalorieProfile.of(70f, "other", 0L, today, zone).sex)
    }
}
