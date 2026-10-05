package com.runner.academy.ui.workout

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.RecyclerView
import com.runner.academy.R
import com.runner.academy.data.WorkoutListItem
import com.runner.academy.data.displayName
import com.runner.academy.databinding.ItemRoutePickerBinding
import java.text.SimpleDateFormat
import java.util.Locale

/** Routes to pick from; rows carry only the preview, the picked track is loaded by id. */
class RoutePickerAdapter(
    private val onRouteClick: (WorkoutListItem) -> Unit
) : PagingDataAdapter<WorkoutListItem, RoutePickerAdapter.RouteViewHolder>(DiffCallback) {

    private val dateFormat = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RouteViewHolder {
        val binding = ItemRoutePickerBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return RouteViewHolder(binding)
    }

    override fun onBindViewHolder(holder: RouteViewHolder, position: Int) {
        // Placeholders are off, so every bound position has a loaded item
        getItem(position)?.let(holder::bind)
    }

    inner class RouteViewHolder(
        private val binding: ItemRoutePickerBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(workout: WorkoutListItem) {
            val context = binding.root.context
            binding.textViewRouteType.text = workout.type.displayName(context)
            binding.textViewRouteMeta.text = context.getString(
                R.string.edit_workout_route_meta,
                workout.distance,
                dateFormat.format(workout.date)
            )
            bindPreview(RoutePreviewState.of(workout))

            binding.imageViewFavorite.visibility =
                if (workout.isFavorite) View.VISIBLE else View.GONE

            binding.root.setOnClickListener { onRouteClick(workout) }
        }

        /**
         * The points caption stays INVISIBLE (not GONE) without a preview, so the text column
         * keeps its height and position. Every state sets its own description: rows are reused.
         */
        private fun bindPreview(state: RoutePreviewState) {
            val context = binding.root.context
            val preview = (state as? RoutePreviewState.Ready)?.preview
            binding.routePreview.setRuns(preview?.runs.orEmpty())
            binding.routePreview.contentDescription = context.getString(
                when (state) {
                    is RoutePreviewState.Ready -> R.string.edit_workout_route_preview_cd
                    RoutePreviewState.Pending -> R.string.workout_list_route_preview_pending_cd
                    RoutePreviewState.NoRoute -> R.string.workout_list_no_route_preview
                }
            )
            if (preview != null) {
                binding.textViewRoutePoints.text = context.resources.getQuantityString(
                    R.plurals.edit_workout_route_points_count,
                    preview.pointCount,
                    preview.pointCount
                )
                binding.textViewRoutePoints.visibility = View.VISIBLE
            } else {
                binding.textViewRoutePoints.text = null
                binding.textViewRoutePoints.visibility = View.INVISIBLE
            }
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<WorkoutListItem>() {
        override fun areItemsTheSame(oldItem: WorkoutListItem, newItem: WorkoutListItem): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: WorkoutListItem, newItem: WorkoutListItem): Boolean =
            oldItem == newItem
    }
}
