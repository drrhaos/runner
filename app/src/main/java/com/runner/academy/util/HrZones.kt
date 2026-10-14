package com.runner.academy.util

import java.time.LocalDate
import java.time.ZoneId

/** Where the maximum heart rate comes from. */
enum class HrMaxSource { AUTO, MANUAL }

/**
 * The maximum heart rate the zones are built on: set by hand ([HrMaxSource.MANUAL]) or
 * 220 − age ([HrMaxSource.AUTO]). Nothing is stored but the manual value: an automatic one
 * follows the birthday, and a change of either recolours every saved workout when it is read.
 */
data class HrMax(val bpm: Int, val source: HrMaxSource) {

    /** The age an automatic value was made from (220 − [bpm]); null for a manual one. */
    val ageYears: Int? get() = if (source == HrMaxSource.AUTO) AGE_FORMULA_BASE - bpm else null

    val zones: HrZones get() = HrZones(bpm)

    companion object {
        const val MIN_MANUAL_BPM = 100
        const val MAX_MANUAL_BPM = 230
        const val MIN_AGE = 10
        const val MAX_AGE = 100
        private const val AGE_FORMULA_BASE = 220

        /**
         * A manual value in [MIN_MANUAL_BPM]..[MAX_MANUAL_BPM] wins ([manualBpm] 0: none set);
         * else 220 − full years on [today] in [zone] for an age of [MIN_AGE]..[MAX_AGE]; else
         * null: the zones are off.
         */
        fun resolve(birthDateMs: Long, manualBpm: Int, today: LocalDate, zone: ZoneId): HrMax? =
            if (manualBpm in MIN_MANUAL_BPM..MAX_MANUAL_BPM) HrMax(manualBpm, HrMaxSource.MANUAL)
            else byAge(birthDateMs, today, zone)

        /** 220 − full years, whatever is set by hand; null without a birth date or out of the ages. */
        fun byAge(birthDateMs: Long, today: LocalDate, zone: ZoneId): HrMax? {
            val age = Ages.fullYears(birthDateMs, today, zone) ?: return null
            if (age !in MIN_AGE..MAX_AGE) return null
            return HrMax(AGE_FORMULA_BASE - age, HrMaxSource.AUTO)
        }
    }
}

/**
 * Five heart rate zones by share of [maxHr]: Z1 50–60 %, Z2 60–70 %, Z3 70–80 %, Z4 80–90 %,
 * Z5 90–100 %. Zone 0 is below Z1.
 */
data class HrZones(val maxHr: Int) {

    /** The lower bounds of Z1..Z5, rounded half up: 50, 60, 70, 80 and 90 % of [maxHr]. */
    val lowerBounds: IntArray = IntArray(COUNT) { i -> (maxHr * (50 + 10 * i) + 50) / 100 }

    /** 0 below Z1, else 1..5; a lower bound belongs to its zone, the upper one does not; at and above [maxHr] 5. */
    fun zoneOf(bpm: Int): Int {
        var zone = BELOW
        for (i in lowerBounds.indices) if (bpm >= lowerBounds[i]) zone = i + 1
        return zone
    }

    /** The beats of [zone] 1..5: up to one below the next zone's bound, Z5 up to [maxHr]. */
    fun range(zone: Int): IntRange {
        require(zone in 1..COUNT) { "zone $zone" }
        val upper = if (zone == COUNT) maxHr else lowerBounds[zone] - 1
        return lowerBounds[zone - 1]..upper
    }

    companion object {
        /** Zones above zone 0. */
        const val COUNT = 5

        /** Below Z1. */
        const val BELOW = 0
    }
}
