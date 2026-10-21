package com.runner.academy.util

import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

/** What the calorie formulas need to know about the runner (see [Calories]). */
data class CalorieProfile(
    val weightKg: Float,
    val sex: Sex,
    /** Full years; null without a birth date (or an implausible one): no heart-rate formula. */
    val ageYears: Int?
) {
    enum class Sex { MALE, FEMALE }

    companion object {
        /** From the settings' profile: [gender] as stored ("female", else male), the birth date as stored (0: none). */
        fun of(weightKg: Float, gender: String, birthDateMs: Long, today: LocalDate, zone: ZoneId) = CalorieProfile(
            weightKg = weightKg,
            sex = if (gender == "female") Sex.FEMALE else Sex.MALE,
            ageYears = Ages.fullYears(birthDateMs, today, zone)?.takeIf { it in HrMax.MIN_AGE..HrMax.MAX_AGE }
        )
    }
}

/**
 * Energy of a run, kcal: one function for the live tile, the saved workout and the form.
 * With an average heart rate and an age — Keytel et al. (2005) without VO2max; otherwise
 * the ACSM running equation (gross, with the resting 3.5 ml/kg/min). Both are linear, so
 * totals give the exact sum over the run: the average heart rate × moving time for Keytel;
 * for ACSM 0.2 × distance + 0.9 × climb (the speed × grade term summed is the climb; ACSM
 * has no downhill term, descents count as flat) + 3.5 × moving minutes.
 */
object Calories {

    private const val KCAL_PER_LITRE_O2 = 5.0
    private const val KJ_PER_KCAL = 4.184
    private const val MS_PER_MIN = 60_000.0

    /**
     * @param climbM the elevation gain; null when unknown (counted as flat)
     * @param avgHeartRate over the moving time; null without a sensor
     */
    fun estimate(profile: CalorieProfile, distanceM: Float, movingMs: Long, climbM: Float?, avgHeartRate: Int?): Int {
        if (movingMs <= 0L) return 0
        val minutes = movingMs / MS_PER_MIN
        val age = profile.ageYears
        val kcal = if (avgHeartRate != null && age != null) {
            keytelPerMinute(profile.sex, avgHeartRate, profile.weightKg, age) * minutes
        } else {
            val oxygenMlPerKg = 0.2 * distanceM.coerceAtLeast(0f) +
                0.9 * (climbM ?: 0f).coerceAtLeast(0f) +
                3.5 * minutes
            oxygenMlPerKg * profile.weightKg / 1000.0 * KCAL_PER_LITRE_O2
        }
        return kcal.coerceAtLeast(0.0).roundToInt()
    }

    private fun keytelPerMinute(sex: CalorieProfile.Sex, hr: Int, weightKg: Float, age: Int): Double {
        val kj = when (sex) {
            CalorieProfile.Sex.MALE -> -55.0969 + 0.6309 * hr + 0.1988 * weightKg + 0.2017 * age
            CalorieProfile.Sex.FEMALE -> -20.4022 + 0.4472 * hr - 0.1263 * weightKg + 0.074 * age
        }
        return kj / KJ_PER_KCAL
    }
}
