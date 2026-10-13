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

    /** "By age" (back to automatic): over a manual value, when there is a birth date to count from. */
    fun offersByAge(current: HrMax?, byAge: HrMax?): Boolean =
        byAge != null && current?.source == HrMaxSource.MANUAL

    /** The zones the picked value gives, Z5 down to Z1: the user sees what changes. */
    fun zonesPreview(bpm: Int): List<Pair<Int, IntRange>> {
        val zones = HrZones(bpm)
        return (HrZones.COUNT downTo 1).map { it to zones.range(it) }
    }
}
