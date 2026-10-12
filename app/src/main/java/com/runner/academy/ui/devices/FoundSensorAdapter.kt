package com.runner.academy.ui.devices

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.runner.academy.R
import com.runner.academy.databinding.ItemFoundSensorBinding
import com.runner.academy.sensor.FoundSensor

/** The devices found by a scan, in the order they were found. */
class FoundSensorAdapter(
    private val onClick: (FoundSensor) -> Unit
) : ListAdapter<FoundRow, FoundSensorAdapter.Holder>(Diff) {

    class Holder(val binding: ItemFoundSensorBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemFoundSensorBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = getItem(position)
        val b = holder.binding
        b.textViewFoundName.text = row.sensor.name ?: b.root.context.getString(R.string.devices_unnamed)
        b.textViewFoundSignal.setText(row.signal.text)
        b.textViewFoundPaired.visibility = if (row.alreadyPaired) View.VISIBLE else View.GONE
        b.root.setOnClickListener { onClick(row.sensor) }
    }

    private object Diff : DiffUtil.ItemCallback<FoundRow>() {
        override fun areItemsTheSame(oldItem: FoundRow, newItem: FoundRow) = oldItem.sensor.address == newItem.sensor.address
        override fun areContentsTheSame(oldItem: FoundRow, newItem: FoundRow) = oldItem == newItem
    }
}
