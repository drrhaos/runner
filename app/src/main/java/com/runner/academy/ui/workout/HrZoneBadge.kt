package com.runner.academy.ui.workout

import android.content.res.ColorStateList
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import com.google.android.material.color.MaterialColors
import com.runner.academy.R
import com.runner.academy.util.HrZones

/**
 * The zone colours (`hr_zone_0..5`, per theme) and the "Z4" badge (`bg_hr_zone_badge`) of the
 * heart-rate tile and the "Time in zones" card. Never colour alone: the badge always says the zone.
 */
object HrZoneBadge {

    @ColorRes
    fun colorOf(zone: Int): Int = when (zone) {
        1 -> R.color.hr_zone_1
        2 -> R.color.hr_zone_2
        3 -> R.color.hr_zone_3
        4 -> R.color.hr_zone_4
        5 -> R.color.hr_zone_5
        else -> R.color.hr_zone_0
    }

    /**
     * "Z4" in the zone's colour with `hr_zone_on` text, or on `hr_zone_stale_bg` with
     * `colorOnSurface` text when [greyed]; zone 0 is "<Z1" (only ever greyed: white on the
     * zone 0 grey is under 4.5:1).
     */
    fun bind(view: TextView, zone: Int, greyed: Boolean) {
        val context = view.context
        view.text = if (zone == HrZones.BELOW) {
            context.getString(R.string.hr_zone_badge_below)
        } else {
            context.getString(R.string.hr_zone_badge, zone)
        }
        val grey = greyed || zone == HrZones.BELOW
        view.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(context, if (grey) R.color.hr_zone_stale_bg else colorOf(zone))
        )
        view.setTextColor(
            if (grey) MaterialColors.getColor(view, com.google.android.material.R.attr.colorOnSurface)
            else ContextCompat.getColor(context, R.color.hr_zone_on)
        )
    }
}
