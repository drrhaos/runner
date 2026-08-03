package com.runner.academy.ui.workout

import android.content.Context
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.runner.academy.R
import com.runner.academy.data.TrackData
import com.runner.academy.data.Workout
import com.runner.academy.data.displayName
import com.runner.academy.databinding.ItemWorkoutBinding
import com.runner.academy.util.SpeedPaceCalculator
import com.runner.academy.util.TrackDataJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale

class WorkoutAdapter(
    private val context: Context,
    private val onItemClick: (Workout) -> Unit,
    private val onFavoriteClick: (Workout) -> Unit
) : PagingDataAdapter<Workout, WorkoutAdapter.WorkoutViewHolder>(WorkoutDiffCallback()) {

    private val trackCache = object : LruCache<Long, Pair<String?, TrackData?>>(32) {}
    private val dateFormat = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
    private val adapterJob = SupervisorJob()
    private val adapterScope = CoroutineScope(adapterJob + Dispatchers.Main.immediate)
    private val previewSizePx = (96f * context.resources.displayMetrics.density).toInt()

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
        trackCache.evictAll()
        super.onDetachedFromRecyclerView(recyclerView)
    }

    private fun resolveTrack(workout: Workout): TrackData? {
        if (workout.trackData.isNullOrBlank()) return null
        synchronized(trackCache) {
            val cached = trackCache.get(workout.id)
            if (cached != null && cached.first == workout.trackData) {
                return cached.second
            }
        }
        val parsed = TrackDataJson.parse(workout.trackData)
        synchronized(trackCache) {
            trackCache.put(workout.id, workout.trackData to parsed)
        }
        return parsed
    }

    inner class WorkoutViewHolder(
        private val binding: ItemWorkoutBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private var previewJob: Job? = null
        private var boundWorkoutId: Long = -1L

        fun bind(workout: Workout) {
            boundWorkoutId = workout.id
            binding.apply {
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
            binding.routePreview.setImageDrawable(null)
            binding.routePreview.visibility = View.INVISIBLE
            binding.textViewRoutePreviewEmpty.visibility = View.VISIBLE
        }

        private fun bindRoutePreview(workout: Workout) {
            previewJob?.cancel()
            if (workout.trackData.isNullOrBlank()) {
                binding.routePreview.setImageDrawable(null)
                binding.routePreview.visibility = View.INVISIBLE
                binding.textViewRoutePreviewEmpty.visibility = View.VISIBLE
                return
            }

            binding.textViewRoutePreviewEmpty.visibility = View.GONE
            binding.routePreview.visibility = View.VISIBLE

            val cacheKey = "${workout.id}:${workout.trackData?.length ?: 0}:$previewSizePx"
            val cached = RouteMapBitmapRenderer.peek(cacheKey)
            if (cached != null) {
                binding.routePreview.setImageBitmap(cached)
                return
            }

            binding.routePreview.setImageDrawable(null)
            val workoutId = workout.id
            previewJob = adapterScope.launch {
                val trackData = withContext(Dispatchers.Default) {
                    resolveTrack(workout)
                }
                if (boundWorkoutId != workoutId || trackData == null || trackData.points.size < 2) {
                    if (boundWorkoutId == workoutId) {
                        binding.routePreview.setImageDrawable(null)
                        binding.routePreview.visibility = View.INVISIBLE
                        binding.textViewRoutePreviewEmpty.visibility = View.VISIBLE
                    }
                    return@launch
                }
                val bitmap = withContext(Dispatchers.IO) {
                    RouteMapBitmapRenderer.getOrRender(
                        context = context,
                        cacheKey = cacheKey,
                        trackData = trackData,
                        widthPx = previewSizePx,
                        heightPx = previewSizePx
                    )
                }
                if (boundWorkoutId == workoutId && bitmap != null) {
                    binding.routePreview.setImageBitmap(bitmap)
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
