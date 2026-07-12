package com.drrhaos.runner.ui.workout

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.drrhaos.runner.R
import com.drrhaos.runner.data.Workout
import com.drrhaos.runner.data.WorkoutType
import com.drrhaos.runner.databinding.ItemWorkoutBinding
import java.text.SimpleDateFormat
import java.util.*

class WorkoutAdapter(
    private val context: Context,
    private val onItemClick: (Workout) -> Unit
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
                // Дата
                val dateFormat = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
                textViewWorkoutDate.text = dateFormat.format(workout.date)

                // Тип тренировки
                textViewWorkoutType.text = getWorkoutTypeDisplayName(workout.type)

                // Дистанция
                textViewDistance.text = String.format("%.1f %s", workout.distance, context.getString(R.string.unit_km))

                // Время
                textViewDuration.text = formatDuration(workout.duration)

                // Темп
                textViewPace.text = formatPace(workout.avgPace)

                // Калории и заметки (если есть)
                if (workout.calories != null || !workout.notes.isNullOrEmpty()) {
                    layoutCalories.visibility = android.view.View.VISIBLE
                    
                    if (workout.calories != null) {
                        textViewCalories.text = "${workout.calories} ${context.getString(R.string.workout_details_calories)}"
                    }
                    
                    if (!workout.notes.isNullOrEmpty()) {
                        textViewNotesPreview.text = workout.notes
                    }
                } else {
                    layoutCalories.visibility = android.view.View.GONE
                }

                // Обработчик клика
                root.setOnClickListener {
                    onItemClick(workout)
                }
            }
        }

        private fun getWorkoutTypeDisplayName(type: WorkoutType): String {
            return when (type) {
                WorkoutType.EASY_RUN -> context.getString(R.string.workout_type_easy_run)
                WorkoutType.TEMPO_RUN -> context.getString(R.string.workout_type_tempo_run)
                WorkoutType.INTERVAL_TRAINING -> context.getString(R.string.workout_type_interval_training)
                WorkoutType.LONG_RUN -> context.getString(R.string.workout_type_long_run)
                WorkoutType.RECOVERY_RUN -> context.getString(R.string.workout_type_recovery_run)
                WorkoutType.RACE -> context.getString(R.string.workout_type_competition)
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
            val minutes = paceMinutes.toInt()
            val seconds = ((paceMinutes - minutes) * 60).toInt()
            return String.format("%d:%02d", minutes, seconds)
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
