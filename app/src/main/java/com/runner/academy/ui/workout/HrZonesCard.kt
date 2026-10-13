package com.runner.academy.ui.workout

import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import com.runner.academy.R
import com.runner.academy.databinding.LayoutHrZoneRowBinding
import com.runner.academy.util.FormatUtils
import com.runner.academy.util.HrMaxSource
import com.runner.academy.util.HrZones

/**
 * The "Time in zones" card of the details from a [HrZonesDisplay]: gone as a whole at
 * [HrZonesDisplay.None], else the basis under the title and six rows, Z5 down to below Z1,
 * each read by TalkBack as one group.
 */
class HrZonesCard(
    private val card: View,
    title: TextView,
    private val basis: TextView,
    private val rowsContainer: LinearLayout
) {

    private val context = card.context
    private var rows: List<LayoutHrZoneRowBinding> = emptyList()

    init {
        ViewCompat.setAccessibilityHeading(title, true)
    }

    fun show(display: HrZonesDisplay) {
        if (display !is HrZonesDisplay.Value) {
            card.visibility = View.GONE
            return
        }
        val source = context.getString(
            if (display.hrMax.source == HrMaxSource.AUTO) R.string.hr_max_source_auto_short
            else R.string.settings_hr_max_source_manual
        )
        basis.text = context.getString(R.string.workout_details_hr_zones_basis, display.hrMax.bpm, source)
        val views = rowViews(display.rows.size)
        display.rows.forEachIndexed { i, row -> bindRow(views[i], row) }
        card.visibility = View.VISIBLE
    }

    private fun rowViews(count: Int): List<LayoutHrZoneRowBinding> {
        if (rows.size != count) {
            rowsContainer.removeAllViews()
            val inflater = LayoutInflater.from(context)
            rows = List(count) {
                LayoutHrZoneRowBinding.inflate(inflater, rowsContainer, true).also { binding ->
                    ViewCompat.setScreenReaderFocusable(binding.root, true)
                }
            }
        }
        return rows
    }

    private fun bindRow(views: LayoutHrZoneRowBinding, row: HrZonesDisplay.Row) {
        val below = row.zone == HrZones.BELOW
        views.textViewZoneBadge.visibility = if (below) View.GONE else View.VISIBLE
        views.textViewZoneBelow.visibility = if (below) View.VISIBLE else View.GONE
        if (!below) HrZoneBadge.bind(views.textViewZoneBadge, row.zone, greyed = false)
        views.textViewZoneRange.text = if (below) {
            context.getString(R.string.workout_details_hr_zone_below_range, row.belowBpm)
        } else {
            context.getString(R.string.workout_details_hr_zone_range, row.range.first, row.range.last)
        }
        views.textViewZoneTime.text = FormatUtils.formatRecordTime(row.ms)
        views.textViewZonePercent.text = context.getString(R.string.workout_details_hr_zone_percent, row.percent)
        views.viewZoneBar.barColor = ContextCompat.getColor(context, HrZoneBadge.colorOf(row.zone))
        views.viewZoneBar.fraction = row.fraction

        val time = FormatUtils.formatTimeForTTS(row.ms, context)
        val percent = context.resources.getQuantityString(R.plurals.percent_a11y, row.percent, row.percent)
        views.root.contentDescription = if (below) {
            context.getString(R.string.workout_details_hr_zone_below_a11y, row.belowBpm, time, percent)
        } else {
            context.getString(R.string.workout_details_hr_zone_row_a11y, row.zone, row.range.first, row.range.last, time, percent)
        }
    }
}
