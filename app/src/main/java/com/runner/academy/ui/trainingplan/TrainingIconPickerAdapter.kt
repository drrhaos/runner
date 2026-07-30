package com.runner.academy.ui.trainingplan

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.runner.academy.R
import com.runner.academy.data.TrainingIcon
import com.runner.academy.data.displayName
import com.runner.academy.data.drawableRes
import com.runner.academy.databinding.ItemTrainingIconBinding

class TrainingIconPickerAdapter(
    private val icons: List<TrainingIcon>,
    private val onSelected: (TrainingIcon) -> Unit
) : RecyclerView.Adapter<TrainingIconPickerAdapter.VH>() {

    var selected: TrainingIcon = icons.first()
        set(value) {
            val old = field
            field = value
            val oldIndex = icons.indexOf(old)
            val newIndex = icons.indexOf(value)
            if (oldIndex >= 0) notifyItemChanged(oldIndex)
            if (newIndex >= 0) notifyItemChanged(newIndex)
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemTrainingIconBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun getItemCount() = icons.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(icons[position])
    }

    inner class VH(private val binding: ItemTrainingIconBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(icon: TrainingIcon) {
            val ctx = binding.root.context
            binding.imageViewIcon.setImageResource(icon.drawableRes())
            binding.imageViewIcon.contentDescription = icon.displayName(ctx)
            val selectedNow = icon == selected
            binding.imageViewIcon.setBackgroundResource(
                if (selectedNow) R.drawable.bg_icon_selected else R.drawable.bg_icon_unselected
            )
            binding.root.setOnClickListener {
                selected = icon
                onSelected(icon)
            }
        }
    }
}
