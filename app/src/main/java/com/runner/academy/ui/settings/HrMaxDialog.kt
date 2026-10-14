package com.runner.academy.ui.settings

import com.runner.academy.util.HrMax
import com.runner.academy.util.HrMaxSource
import com.runner.academy.util.HrZones

/** What the maximum heart rate dialog of the settings shows and saves. */
object HrMaxDialog {

    /** Where the picker starts without an HRmax. */
    const val DEFAULT_BPM = 190

    fun initialBpm(current: HrMax?): Int = current?.bpm ?: DEFAULT_BPM

    /**
     * The manual value to write on Save, or null to write nothing: an automatic value saved
     * untouched stays "by age" instead of turning into a manual one by accident.
     */
    fun manualToSave(current: HrMax?, pickedBpm: Int): Int? =
        if (current?.source == HrMaxSource.AUTO && pickedBpm == current.bpm) null else pickedBpm

    /** What the neutral button does; both clear the manual value. */
    enum class Neutral {
        /** Back to 220 − age. */
        BY_AGE,

        /** No age to count from: the zones go off. */
        ZONES_OFF
    }

    /**
     * Over a manual value the neutral button clears it: "By age" when a birth date gives one,
     * else "Turn zones off" (a manual value is the only way out otherwise). Null: nothing to clear.
     */
    fun neutral(current: HrMax?, byAge: HrMax?): Neutral? = when {
        current?.source != HrMaxSource.MANUAL -> null
        byAge != null -> Neutral.BY_AGE
        else -> Neutral.ZONES_OFF
    }

    /** The text above the picker. */
    enum class Explanation {
        /** "By age: 220 − 40 = 180…" */
        FORMULA,
        NO_BIRTH_DATE,

        /** A birth date is set, but the age is out of [HrMax.MIN_AGE]..[HrMax.MAX_AGE]. */
        AGE_OUT_OF_RANGE
    }

    /** [birthDateMs] 0: not set (a date before 1970 is negative and real). */
    fun explanation(birthDateMs: Long, byAge: HrMax?): Explanation = when {
        byAge != null -> Explanation.FORMULA
        birthDateMs == 0L -> Explanation.NO_BIRTH_DATE
        else -> Explanation.AGE_OUT_OF_RANGE
    }

    /** The zones the picked value gives, Z5 down to Z1: the user sees what changes. */
    fun zonesPreview(bpm: Int): List<Pair<Int, IntRange>> {
        val zones = HrZones(bpm)
        return (HrZones.COUNT downTo 1).map { it to zones.range(it) }
    }
}
