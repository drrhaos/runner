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
        // Null: a placeholder for a row not loaded yet (the list has its full length for the
        // fast scroller); it is bound again once loaded
        val workout = getItem(position) ?: return holder.bindPlaceholder()
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
                textViewWorkoutType.text = WorkoutTitle.label(workout.title, workout.type.displayName(context))
                textViewWorkoutType.visibility = View.VISIBLE
                // Bare numbers as in the details: the units are in the captions
                textViewDistance.text = MetricValue.distance(workout.distance)
                // Moving time: distance / time = pace adds up on the card
                textViewDuration.text = FormatUtils.formatTime(workout.movingDuration)
                textViewPace.text = MetricValue.pace(workout.avgPace)
                updateFavoriteButton(workout)
                bindRoutePreview(workout)

                // Calories and the note share a line; one alone takes it whole, no empty third
                val calories = workout.calories
                val notes = workout.notes.orEmpty()
                layoutCalories.visibility = if (calories != null || notes.isNotEmpty()) View.VISIBLE else View.GONE
                textViewCalories.visibility = if (calories != null) View.VISIBLE else View.GONE
                textViewCalories.text = calories?.let { FormatUtils.formatCalories(it, context) }
                textViewNotesPreview.visibility = if (notes.isNotEmpty()) View.VISIBLE else View.GONE
                textViewNotesPreview.text = notes

                buttonFavorite.visibility = View.VISIBLE
                root.setOnClickListener { onItemClick(workout) }
                buttonFavorite.setOnClickListener { onFavoriteClick(workout) }
            }
        }

        /** An empty card of the same height: no stale workout shows while the row loads. */
        fun bindPlaceholder() {
            previewJob?.cancel()
            previewJob = null
            boundWorkoutId = -1L
            binding.apply {
                // The slot's background alone, without "no route"
                routePreview.setImageDrawable(null)
                routePreview.visibility = View.VISIBLE
                routePreview.contentDescription = null
                textViewRoutePreviewEmpty.visibility = View.GONE
                textViewWorkoutDate.text = ""
                textViewWorkoutType.text = ""
                textViewWorkoutType.visibility = View.INVISIBLE
                textViewDistance.text = ""
                textViewDuration.text = ""
                textViewPace.text = ""
                layoutCalories.visibility = View.GONE
                buttonFavorite.visibility = View.INVISIBLE
                root.setOnClickListener(null)
                buttonFavorite.setOnClickListener(null)
            }
        }

        fun clearPreview() {
            previewJob?.cancel()
            previewJob = null
            boundWorkoutId = -1L
            showNoRoute()
        }

        /** "No route": TalkBack reads the text; the invisible image has nothing to say. */
        private fun showNoRoute() {
            binding.routePreview.setImageDrawable(null)
            binding.routePreview.visibility = View.INVISIBLE
            binding.routePreview.contentDescription = null
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
            // The whole preview text, not its hash: a collision would show another route
            val cacheKey = "${workout.id}:$previewSizePx:$night:${workout.routePreview}"
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
