package com.runner.academy.ui.trainingplan

/**
 * How the «Zone» choice of the segment editor looks (design §2). With the zones off the button
 * is disabled and a hint points to the settings, except for a segment that already has a zone:
 * its button stays selected and enabled (the zone is never erased), but the picker cannot open
 * without a range to show. Once the user leaves «Zone», the button is disabled again.
 */
data class ZoneTargetUi(
    val zoneButtonEnabled: Boolean,
    val pickerAllowed: Boolean,
    val hintVisible: Boolean
) {
    companion object {
        fun of(selected: TargetKind, zonesOn: Boolean) = ZoneTargetUi(
            zoneButtonEnabled = zonesOn || selected == TargetKind.ZONE,
            pickerAllowed = zonesOn,
            hintVisible = !zonesOn
        )
    }
}
