package com.runner.academy.ui.tracking

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.runner.academy.R
import com.runner.academy.data.SegmentTarget
import com.runner.academy.util.FormatUtils
import com.runner.academy.util.SpeedPaceCalculator
import com.runner.academy.voice.TargetState
import com.runner.academy.voice.TargetVerdict
import kotlin.math.roundToInt

/**
 * What the segment target's indicator shows (design §5.2): the circle under the current segment
 * on the bar and the verdict line of the interval panel. The words are the voice's ("Speed up"),
 * the arrow is what to do (up: faster or a higher heart rate), the value is the one the verdict
 * was made from ("Speed up · 4:45"). The screen is in kilometres.
 *
 * @param text the verdict line; «≈» first for a pace by steps.
 * @param description for TalkBack's announcement: the value in words, "approximate from steps"
 *   for a steps pace.
 * @param outlined the circle is drawn as a ring (a steps pace), not filled.
 * @param stateDescription the bar's state for TalkBack: the verdict (and "approximate") without
 *   the value, so it changes with the verdict, not with every second's pace.
 */
data class TargetIndicatorLook(
    val text: String,
    val description: String,
    val tone: Tone,
    @DrawableRes val icon: Int,
    val outlined: Boolean,
    val stateDescription: String
) {
    /** In the target (green), off it (orange) or neutral (`colorOnSurfaceVariant`). */
    enum class Tone { IN, OFF, NEUTRAL }

    companion object {
        /**
         * Null without an indicator: no state yet, a segment without a target, or the workout
         * over. [zonesOn] tells "no heart rate" from "zones are off" for a zone without data.
         */
        fun of(state: TargetState?, target: SegmentTarget, zonesOn: Boolean, context: Context): TargetIndicatorLook? {
            if (state == null || target == SegmentTarget.None) return null
            val zoneTarget = target is SegmentTarget.HrZone
            val (word, tone, icon) = when (state.verdict) {
                TargetVerdict.NO_TARGET -> return null
                TargetVerdict.IN -> Triple(R.string.target_state_in, Tone.IN, R.drawable.ic_target_in)
                TargetVerdict.TOO_SLOW -> Triple(R.string.target_state_faster, Tone.OFF, R.drawable.ic_target_up)
                TargetVerdict.TOO_FAST -> Triple(R.string.target_state_slower, Tone.OFF, R.drawable.ic_target_down)
                TargetVerdict.BELOW_ZONE -> Triple(R.string.target_state_below_zone, Tone.OFF, R.drawable.ic_target_up)
                TargetVerdict.ABOVE_ZONE -> Triple(R.string.target_state_above_zone, Tone.OFF, R.drawable.ic_target_down)
                TargetVerdict.PENDING -> Triple(
                    if (zoneTarget) R.string.target_state_pending_zone else R.string.target_state_pending_pace,
                    Tone.NEUTRAL,
                    R.drawable.ic_target_wait
                )
                TargetVerdict.NO_DATA -> Triple(noDataText(zoneTarget, zonesOn), Tone.NEUTRAL, R.drawable.ic_target_wait)
            }
            val wordText = context.getString(word)
            val value = state.value?.takeIf { tone != Tone.NEUTRAL && it.isFinite() && it > 0f }
            var text = wordText
            var description = wordText
            if (value != null) {
                val (digits, spoken) = if (zoneTarget) {
                    val bpm = value.roundToInt()
                    bpm.toString() to context.resources.getQuantityString(R.plurals.heart_rate_beats_per_minute, bpm, bpm)
                } else {
                    SpeedPaceCalculator.formatPaceMmSs(value) to FormatUtils.formatPaceForTTS(value, context)
                }
                text = context.getString(R.string.target_state_with_value, wordText, digits)
                description = context.getString(R.string.target_state_with_value_a11y, wordText, spoken)
            }
            if (state.approximate) {
                text = context.getString(R.string.target_state_approx, text)
                description = context.getString(R.string.target_state_approx_a11y, description)
            }
            val stateDescription = if (state.approximate) {
                context.getString(R.string.target_state_approx_a11y, wordText)
            } else {
                wordText
            }
            return TargetIndicatorLook(text, description, tone, icon, outlined = state.approximate, stateDescription = stateDescription)
        }

        @StringRes
        private fun noDataText(zoneTarget: Boolean, zonesOn: Boolean): Int = when {
            !zoneTarget -> R.string.target_state_no_pace
            zonesOn -> R.string.target_state_no_hr
            else -> R.string.target_state_zones_off
        }
    }
}
