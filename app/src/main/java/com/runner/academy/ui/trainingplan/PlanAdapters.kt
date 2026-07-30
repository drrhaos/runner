package com.runner.academy.ui.trainingplan

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.runner.academy.R
import com.runner.academy.data.WorkoutTemplate
import com.runner.academy.data.displayName
import com.runner.academy.databinding.ItemSimpleTwoLineBinding

class TemplateListAdapter(
    private val onClick: (WorkoutTemplate) -> Unit
) : ListAdapter<WorkoutTemplate, TemplateListAdapter.VH>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemSimpleTwoLineBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    inner class VH(private val binding: ItemSimpleTwoLineBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(item: WorkoutTemplate) {
            binding.textViewTitle.text = item.name
            binding.textViewSubtitle.text = item.workoutType.displayName(binding.root.context)
            binding.root.setOnClickListener { onClick(item) }
        }
    }

    private object Diff : DiffUtil.ItemCallback<WorkoutTemplate>() {
        override fun areItemsTheSame(a: WorkoutTemplate, b: WorkoutTemplate) = a.id == b.id
        override fun areContentsTheSame(a: WorkoutTemplate, b: WorkoutTemplate) = a == b
    }
}

class PlanListAdapter(
    private val onClick: (com.runner.academy.data.TrainingPlan) -> Unit
) : ListAdapter<com.runner.academy.data.TrainingPlan, PlanListAdapter.VH>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemSimpleTwoLineBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    inner class VH(private val binding: ItemSimpleTwoLineBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(item: com.runner.academy.data.TrainingPlan) {
            binding.textViewTitle.text = item.name
            binding.textViewSubtitle.text = binding.root.context.getString(
                R.string.plan_duration_days_format,
                item.durationDays
            )
            binding.root.setOnClickListener { onClick(item) }
        }
    }

    private object Diff : DiffUtil.ItemCallback<com.runner.academy.data.TrainingPlan>() {
        override fun areItemsTheSame(
            a: com.runner.academy.data.TrainingPlan,
            b: com.runner.academy.data.TrainingPlan
        ) = a.id == b.id

        override fun areContentsTheSame(
            a: com.runner.academy.data.TrainingPlan,
            b: com.runner.academy.data.TrainingPlan
        ) = a == b
    }
}
