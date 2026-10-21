package com.runner.academy.ui.tracking

import androidx.annotation.AttrRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.runner.academy.R
import com.runner.academy.sensor.HeartRateDisplay
import com.runner.academy.util.HrZones

/**
 * The link icon of the heart-rate tile and of its compact copy by the GPS indicator. The state
 * is never told by colour alone: the icon, its tint and the TalkBack state go together.
 *
 * @param stateDescription the tile's `stateDescription`, the compact icon's `contentDescription`.
 */
enum class HrLinkIcon(
    @DrawableRes val drawable: Int,
    @AttrRes val tintAttr: Int,
    @StringRes val stateDescription: Int
) {
    CONNECTED(R.drawable.ic_hr_link_connected, androidx.appcompat.R.attr.colorPrimary, R.string.tracking_hr_link_connected),

    /** Connected, the strap is off. */
    NO_CONTACT(R.drawable.ic_hr_link_connected, com.google.android.material.R.attr.colorError, R.string.devices_status_no_contact),
    SEARCHING(R.drawable.ic_hr_link_searching, com.google.android.material.R.attr.colorOnSurfaceVariant, R.string.tracking_hr_link_searching),
    LOST(R.drawable.ic_hr_link_lost, com.google.android.material.R.attr.colorError, R.string.tracking_hr_link_lost)
}

/**
 * How the heart-rate tile looks for a [HeartRateDisplay] (null: no sensor). One function for
 * both sources: the service's session during a workout, the screen's own lease before it.
 *
 * @param text the value; null: no value, the placeholder `track_info_pulse_placeholder` (`--`).
 * @param greyed the value is 5–15 s old: `colorOnSurfaceVariant` instead of `colorOnSurface`.
 * @param icon null: no sensor, the icon is gone.
 * @param zone the zone of the shown value (0 below Z1); null: no value or the zones are off.
 * @param badge the zone badge by the value; null: gone.
 */
data class HeartRateTileLook(
    val text: String?,
    val greyed: Boolean,
    val icon: HrLinkIcon?,
    val a11y: A11y,
    val zone: Int? = null,
    val badge: ZoneBadge? = null
) {

    /**
     * The tile's `contentDescription`: [text], with the `heart_rate_beats_per_minute` plural of
     * [bpm] and then [zone] when the text takes one.
     */
    data class A11y(@StringRes val text: Int, val bpm: Int? = null, val zone: Int? = null)

    /**
     * "Z4" in the zone's colour, or on the grey background when [greyed]: a stale value, or a
     * fresh one below Z1 ([zone] 0, "<Z1": told apart from zones that are off).
     */
    data class ZoneBadge(val zone: Int, val greyed: Boolean)

    companion object {
        fun of(display: HeartRateDisplay?, zones: HrZones? = null): HeartRateTileLook {
            if (display == null) {
                return HeartRateTileLook(null, false, null, A11y(R.string.tracking_hr_tile_no_sensor_a11y))
            }
            val icon = when {
                display.noContact -> HrLinkIcon.NO_CONTACT
                display.link == HeartRateDisplay.LinkIcon.CONNECTED -> HrLinkIcon.CONNECTED
                display.link == HeartRateDisplay.LinkIcon.SEARCHING -> HrLinkIcon.SEARCHING
                else -> HrLinkIcon.LOST
            }
            val bpm = display.bpm
                ?: return HeartRateTileLook(null, false, icon, A11y(R.string.tracking_hr_tile_no_data_a11y))
            val fresh = display.isFresh
            val zone = zones?.zoneOf(bpm)
            val badge = when {
                zone == null -> null
                zone == HrZones.BELOW -> if (fresh) ZoneBadge(zone, greyed = true) else null
                else -> ZoneBadge(zone, greyed = !fresh)
            }
            val a11y = when {
                zone == null -> A11y(if (fresh) R.string.tracking_hr_tile_a11y else R.string.tracking_hr_tile_stale_a11y, bpm)
                zone == HrZones.BELOW ->
                    A11y(if (fresh) R.string.tracking_hr_tile_below_zone_a11y else R.string.tracking_hr_tile_stale_a11y, bpm)
                fresh -> A11y(R.string.tracking_hr_tile_zone_a11y, bpm, zone)
                else -> A11y(R.string.tracking_hr_tile_stale_zone_a11y, bpm, zone)
            }
            return HeartRateTileLook(bpm.toString(), !fresh, icon, a11y, zone, badge)
        }
    }
}
