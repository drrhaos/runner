package com.runner.academy.ui.records

import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.runner.academy.R
import com.runner.academy.data.RecordDistance
import com.runner.academy.data.displayName
import com.runner.academy.databinding.ItemRecordBinding
import com.runner.academy.databinding.ItemRecordHistoryBinding
import com.runner.academy.util.FormatUtils

/** One card per record distance, its history unfolded on demand. */
class RecordsAdapter(
    private val onWorkoutClick: (workoutId: Long) -> Unit,
    private val onHistoryToggle: (RecordDistance) -> Unit
) : ListAdapter<RecordsAdapter.Item, RecordsAdapter.ViewHolder>(DIFF) {

    data class Item(val row: RecordsRow, val expanded: Boolean)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(ItemRecordBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position))

    inner class ViewHolder(private val binding: ItemRecordBinding) : RecyclerView.ViewHolder(binding.root) {

        init {
            ViewCompat.setAccessibilityHeading(binding.textViewRecordDistance, true)
        }

        fun bind(item: Item) {
            val context = binding.root.context
            val row = item.row
            val name = context.getString(RecordsText.nameRes(row.distance))
            val spokenName = context.getString(RecordsText.spokenNameRes(row.distance))
            binding.textViewRecordDistance.text = name

            val record = row.record
            if (record == null) {
                binding.textViewRecordTime.text = context.getString(R.string.records_not_reached_value)
                binding.textViewRecordMeta.text =
                    context.getString(R.string.records_not_reached_hint, RecordsText.distanceToRun(context, row.distance))
                binding.imageViewRecordChevron.visibility = View.GONE
                binding.rowRecordMain.setOnClickListener(null)
                binding.rowRecordMain.isClickable = false
                binding.rowRecordMain.isFocusable = true
                binding.rowRecordMain.contentDescription =
                    context.getString(R.string.records_item_not_reached_a11y, spokenName)
            } else {
                binding.textViewRecordTime.text = FormatUtils.formatRecordTime(record.effort.elapsedMs)
                binding.textViewRecordMeta.text = context.getString(
                    R.string.records_meta_format,
                    RecordsText.pace(context, record.effort),
                    FormatUtils.formatDate(record.date),
                    record.type.displayName(context)
                )
                binding.imageViewRecordChevron.visibility = View.VISIBLE
                binding.rowRecordMain.setOnClickListener { onWorkoutClick(record.effort.workoutId) }
                binding.rowRecordMain.contentDescription = context.getString(
                    R.string.records_item_a11y,
                    spokenName,
                    FormatUtils.formatTimeForTTS(record.effort.elapsedMs, context),
                    RecordsText.spokenDate(record.date)
                )
            }
            binding.textViewRecordStepsNote.visibility = if (row.approximate) View.VISIBLE else View.GONE

            bindHistory(item)
        }

        private fun bindHistory(item: Item) {
            val context = binding.root.context
            val row = item.row
            val button = binding.buttonRecordHistory
            if (!row.showsHistory) {
                button.visibility = View.GONE
                binding.layoutRecordHistory.visibility = View.GONE
                binding.layoutRecordHistory.removeAllViews()
                return
            }
            val toggle = context.getString(R.string.records_history_toggle, row.history.size)
            val state = context.getString(
                if (item.expanded) R.string.records_history_expanded else R.string.records_history_collapsed
            )
            button.visibility = View.VISIBLE
            button.text = toggle
            button.setIconResource(if (item.expanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                button.stateDescription = state
                button.contentDescription = null
            } else {
                button.contentDescription = "$toggle, $state"
            }
            button.setOnClickListener { onHistoryToggle(row.distance) }

            val container = binding.layoutRecordHistory
            container.removeAllViews()
            if (!item.expanded) {
                container.visibility = View.GONE
                return
            }
            container.visibility = View.VISIBLE
            val inflater = LayoutInflater.from(context)
            for (entry in row.history) {
                val line = ItemRecordHistoryBinding.inflate(inflater, container, false)
                val date = FormatUtils.formatDate(entry.row.date)
                val spokenDate = RecordsText.spokenDate(entry.row.date)
                val spokenTime = FormatUtils.formatTimeForTTS(entry.row.effort.elapsedMs, context)
                line.textViewHistoryDate.text = date
                line.textViewHistoryTime.text = FormatUtils.formatRecordTime(entry.row.effort.elapsedMs)
                val improvement = entry.improvementMs
                if (improvement == null) {
                    line.textViewHistoryDelta.text = context.getString(R.string.records_history_first)
                    line.root.contentDescription =
                        context.getString(R.string.records_history_first_a11y, spokenDate, spokenTime)
                } else {
                    line.textViewHistoryDelta.text =
                        context.getString(R.string.records_history_delta, FormatUtils.formatRecordTime(improvement))
                    line.root.contentDescription = context.getString(
                        R.string.records_history_item_a11y,
                        spokenDate,
                        spokenTime,
                        FormatUtils.formatTimeForTTS(improvement, context)
                    )
                }
                val workoutId = entry.row.effort.workoutId
                line.root.setOnClickListener { onWorkoutClick(workoutId) }
                container.addView(line.root)
            }
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<Item>() {
            override fun areItemsTheSame(oldItem: Item, newItem: Item) = oldItem.row.distance == newItem.row.distance
            override fun areContentsTheSame(oldItem: Item, newItem: Item) = oldItem == newItem
        }
    }
}
