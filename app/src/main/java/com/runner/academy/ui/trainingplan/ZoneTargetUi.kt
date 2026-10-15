package com.runner.academy.ui.trainingplan

import com.runner.academy.util.HrZones

/**
 * How the «Zone» choice of one segment card behaves (design §2, architect's decision on the hint).
 * With the zones off the button is disabled, except for a segment that already has a zone: its
 * button stays selected and enabled (the zone is never erased), but the picker cannot open
 * without a range to show. Once the user leaves «Zone», the button is disabled again. The
 * «zones are off» hint is not per card: the editor shows it once above the segment list.
 */
data class ZoneTargetUi(
    val zoneButtonEnabled: Boolean,
    /** The zones the picker shows; null — no picker (a tap on the field explains why). */
    val pickerZones: HrZones?
) {
    val pickerAllowed: Boolean get() = pickerZones != null

    companion object {
        fun of(selected: TargetKind, zones: HrZones?) = ZoneTargetUi(
            zoneButtonEnabled = zones != null || selected == TargetKind.ZONE,
            pickerZones = zones
        )
    }
}
