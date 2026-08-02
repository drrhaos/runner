package com.runner.academy.ui.trainingplan

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.runner.academy.R
import com.runner.academy.data.TrainingIcon
import com.runner.academy.data.WorkoutTemplate
import com.runner.academy.data.WorkoutTemplateWithSegments
import com.runner.academy.data.displayName
import com.runner.academy.data.drawableRes
import com.runner.academy.data.parseTrainingIcon
import com.runner.academy.databinding.ItemSimpleTwoLineBinding
import com.runner.academy.databinding.ItemTemplateListBinding

class TemplateListAdapter(
    private val onClick: (WorkoutTemplate) -> Unit
) : ListAdapter<WorkoutTemplateWithSegments, TemplateListAdapter.VH>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemTemplateListBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    inner class VH(private val binding: ItemTemplateListBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(item: WorkoutTemplateWithSegments) {
            val template = item.template
            val ctx = binding.root.context
            val icon = parseTrainingIcon(template.iconKey, TrainingIcon.INTERVAL)
            binding.imageViewIcon.setImageResource(icon.drawableRes())
            binding.textViewTitle.text = template.name
            binding.textViewSubtitle.text = template.workoutType.displayName(ctx)

            if (item.segments.isEmpty()) {
                binding.progressTemplateScheme.visibility = View.GONE
                binding.progressTemplateScheme.setSegments(emptyList())
            } else {
                binding.progressTemplateScheme.visibility = View.VISIBLE
                binding.progressTemplateScheme.setSegments(item.segments)
                // All segments fully colored — static scheme preview (no "current" highlight).
                binding.progressTemplateScheme.setProgress(item.segments.size, 1f)
            }
            binding.root.setOnClickListener { onClick(template) }
        }
    }

    private object Diff : DiffUtil.ItemCallback<WorkoutTemplateWithSegments>() {
        override fun areItemsTheSame(
            a: WorkoutTemplateWithSegments,
            b: WorkoutTemplateWithSegments
        ) = a.template.id == b.template.id

        override fun areContentsTheSame(
            a: WorkoutTemplateWithSegments,
            b: WorkoutTemplateWithSegments
        ) = a == b
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
            val icon = parseTrainingIcon(item.iconKey, TrainingIcon.PLAN)
            binding.imageViewIcon.setImageResource(icon.drawableRes())
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
