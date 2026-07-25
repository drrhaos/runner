package com.runner.academy.ui.workout

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.runner.academy.R
import com.runner.academy.data.Workout
import com.runner.academy.data.displayName
import com.runner.academy.databinding.ItemWorkoutBinding
import com.runner.academy.util.SpeedPaceCalculator
import java.text.SimpleDateFormat
import java.util.*

class WorkoutAdapter(
    private val context: Context,
    private val onItemClick: (Workout) -> Unit,
    private val onFavoriteClick: (Workout) -> Unit
) : ListAdapter<Workout, WorkoutAdapter.WorkoutViewHolder>(WorkoutDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): WorkoutViewHolder {
        val binding = ItemWorkoutBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return WorkoutViewHolder(binding)
    }

    override fun onBindViewHolder(holder: WorkoutViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class WorkoutViewHolder(
        private val binding: ItemWorkoutBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(workout: Workout) {
            binding.apply {
                val dateFormat = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
                textViewWorkoutDate.text = dateFormat.format(workout.date)

                textViewWorkoutType.text = workout.type.displayName(context)

                textViewDistance.text = String.format(
                    "%.1f %s",
                    workout.distance,
                    context.getString(R.string.unit_km)
                )

                textViewDuration.text = formatDuration(workout.duration)

                textViewPace.text = formatPace(workout.avgPace)

                updateFavoriteButton(workout)

                if (workout.calories != null || !workout.notes.isNullOrEmpty()) {
                    layoutCalories.visibility = android.view.View.VISIBLE

                    if (workout.calories != null) {
                        textViewCalories.text =
                            "${workout.calories} ${context.getString(R.string.workout_details_calories)}"
                    }

                    if (!workout.notes.isNullOrEmpty()) {
                        textViewNotesPreview.text = workout.notes
                    }
                } else {
                    layoutCalories.visibility = android.view.View.GONE
                }

                root.setOnClickListener {
                    onItemClick(workout)
                }
                buttonFavorite.setOnClickListener {
                    onFavoriteClick(workout)
                }
            }
        }

        private fun updateFavoriteButton(workout: Workout) {
            if (workout.isFavorite) {
                binding.buttonFavorite.setImageResource(R.drawable.ic_star)
                binding.buttonFavorite.contentDescription =
                    context.getString(R.string.workout_favorite_remove)
            } else {
                binding.buttonFavorite.setImageResource(R.drawable.ic_star_border)
                binding.buttonFavorite.contentDescription =
                    context.getString(R.string.workout_favorite_add)
            }
        }

        private fun formatDuration(durationMs: Long): String {
            val totalSeconds = durationMs / 1000
            val hours = totalSeconds / 3600
            val minutes = (totalSeconds % 3600) / 60
            val seconds = totalSeconds % 60

            return when {
                hours > 0 -> String.format("%d:%02d:%02d", hours, minutes, seconds)
                else -> String.format("%d:%02d", minutes, seconds)
            }
        }

        private fun formatPace(paceMinutes: Float): String {
            return SpeedPaceCalculator.formatPaceMmSs(paceMinutes)
        }
    }

    private class WorkoutDiffCallback : DiffUtil.ItemCallback<Workout>() {
        override fun areItemsTheSame(oldItem: Workout, newItem: Workout): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Workout, newItem: Workout): Boolean {
            return oldItem == newItem
        }
    }
}
