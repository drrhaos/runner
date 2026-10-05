package com.runner.academy.ui.workout

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.runner.academy.R
import com.runner.academy.data.WorkoutListItem
import com.runner.academy.data.displayName
import com.runner.academy.databinding.ItemWorkoutBinding
import com.runner.academy.util.FormatUtils
import com.runner.academy.util.SpeedPaceCalculator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

/** Workout list rows; the route preview is drawn from [WorkoutListItem.routePreview], never the track. */
class WorkoutAdapter(
    private val context: Context,
    private val onItemClick: (WorkoutListItem) -> Unit,
    private val onFavoriteClick: (WorkoutListItem) -> Unit
) : PagingDataAdapter<WorkoutListItem, WorkoutAdapter.WorkoutViewHolder>(WorkoutDiffCallback()) {

    private val dateFormat = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
    private val adapterJob = SupervisorJob()
    private val adapterScope = CoroutineScope(adapterJob + Dispatchers.Main.immediate)
    private val previewSizePx = (88f * context.resources.displayMetrics.density).toInt()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): WorkoutViewHolder {
        val binding = ItemWorkoutBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return WorkoutViewHolder(binding)
    }

    override fun onBindViewHolder(holder: WorkoutViewHolder, position: Int) {
        val workout = getItem(position) ?: return
        holder.bind(workout)
    }

    override fun onViewRecycled(holder: WorkoutViewHolder) {
        holder.clearPreview()
        super.onViewRecycled(holder)
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        adapterJob.cancelChildren()
        super.onDetachedFromRecyclerView(recyclerView)
    }

    inner class WorkoutViewHolder(
        private val binding: ItemWorkoutBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private var previewJob: Job? = null
        private var boundWorkoutId: Long = -1L

        fun bind(workout: WorkoutListItem) {
            boundWorkoutId = workout.id
            binding.apply {
                textViewWorkoutDate.text = dateFormat.format(workout.date)
                textViewWorkoutType.text = workout.type.displayName(context)
                textViewDistance.text = String.format(
                    "%.1f %s",
                    workout.distance,
                    context.getString(R.string.unit_km)
                )
                textViewDuration.text = FormatUtils.formatTime(workout.duration)
                textViewPace.text = formatPace(workout.avgPace)
                updateFavoriteButton(workout)
                bindRoutePreview(workout)

                if (workout.calories != null || !workout.notes.isNullOrEmpty()) {
                    layoutCalories.visibility = View.VISIBLE
                    textViewCalories.text = if (workout.calories != null) {
                        "${workout.calories} ${context.getString(R.string.workout_details_calories)}"
                    } else {
                        ""
                    }
                    textViewNotesPreview.text = workout.notes.orEmpty()
                } else {
                    layoutCalories.visibility = View.GONE
                }

                root.setOnClickListener { onItemClick(workout) }
                buttonFavorite.setOnClickListener { onFavoriteClick(workout) }
            }
        }

        fun clearPreview() {
            previewJob?.cancel()
            previewJob = null
            boundWorkoutId = -1L
            showNoRoute()
        }

        /** "No route": the text is read by TalkBack, the invisible image keeps its description. */
        private fun showNoRoute() {
            binding.routePreview.setImageDrawable(null)
            binding.routePreview.visibility = View.INVISIBLE
            binding.routePreview.contentDescription = context.getString(R.string.edit_workout_route_preview_cd)
            binding.textViewRoutePreviewEmpty.visibility = View.VISIBLE
        }

        /** The empty slot (its background only); also what shows while a bitmap renders. */
        private fun showEmptySlot(descriptionRes: Int) {
            binding.routePreview.setImageDrawable(null)
            binding.routePreview.visibility = View.VISIBLE
            binding.routePreview.contentDescription = context.getString(descriptionRes)
            binding.textViewRoutePreviewEmpty.visibility = View.GONE
        }

        private fun bindRoutePreview(workout: WorkoutListItem) {
            previewJob?.cancel()
            val preview = when (val state = RoutePreviewState.of(workout)) {
                RoutePreviewState.NoRoute -> return showNoRoute()
                RoutePreviewState.Pending -> return showEmptySlot(R.string.workout_list_route_preview_pending_cd)
                is RoutePreviewState.Ready -> state.preview
            }

            val night = com.runner.academy.util.OsmMapTiles.isNightMode(binding.root.context)
            val cacheKey = "${workout.id}:${workout.routePreview.hashCode()}:$previewSizePx:$night"
            val cached = RouteMapBitmapRenderer.peek(cacheKey)
            if (cached != null) {
                showEmptySlot(R.string.edit_workout_route_preview_cd)
                binding.routePreview.setImageBitmap(cached)
                return
            }

            showEmptySlot(R.string.edit_workout_route_preview_cd)
            val workoutId = workout.id
            previewJob = adapterScope.launch {
                val bitmap = RouteMapBitmapRenderer.getOrRender(
                    context = context,
                    cacheKey = cacheKey,
                    runs = preview.runs,
                    widthPx = previewSizePx,
                    heightPx = previewSizePx
                )
                if (boundWorkoutId == workoutId && bitmap != null) {
                    binding.routePreview.setImageBitmap(bitmap)
                }
            }
        }

        private fun updateFavoriteButton(workout: WorkoutListItem) {
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

        private fun formatPace(paceMinutes: Float): String {
            return SpeedPaceCalculator.formatPaceMmSs(paceMinutes)
        }
    }

    private class WorkoutDiffCallback : DiffUtil.ItemCallback<WorkoutListItem>() {
        override fun areItemsTheSame(oldItem: WorkoutListItem, newItem: WorkoutListItem): Boolean {
            return oldItem.id == newItem.id
        }

        /** A small data class without the track: plain equality is cheap. */
        override fun areContentsTheSame(oldItem: WorkoutListItem, newItem: WorkoutListItem): Boolean {
            return oldItem == newItem
        }
    }
}
