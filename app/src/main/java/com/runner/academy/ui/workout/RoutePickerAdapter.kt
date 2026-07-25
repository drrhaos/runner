package com.runner.academy.ui.workout

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.runner.academy.R
import com.runner.academy.data.TrackData
import com.runner.academy.data.Workout
import com.runner.academy.data.displayName
import com.runner.academy.databinding.ItemRoutePickerBinding
import com.google.gson.Gson
import java.text.SimpleDateFormat
import java.util.Locale

class RoutePickerAdapter(
    private val onRouteClick: (Workout) -> Unit
) : ListAdapter<Workout, RoutePickerAdapter.RouteViewHolder>(DiffCallback) {

    private val gson = Gson()
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
        holder.bind(getItem(position))
    }

    inner class RouteViewHolder(
        private val binding: ItemRoutePickerBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(workout: Workout) {
            val context = binding.root.context
            binding.textViewRouteType.text = workout.type.displayName(context)
            binding.textViewRouteMeta.text = context.getString(
                R.string.edit_workout_route_meta,
                workout.distance,
                dateFormat.format(workout.date)
            )

            val trackData = parseTrack(workout.trackData)
            val pointCount = trackData?.points?.size ?: 0
            binding.textViewRoutePoints.text = context.getString(
                R.string.edit_workout_route_points,
                pointCount
            )
            binding.routePreview.setTrackData(trackData)

            binding.imageViewFavorite.visibility =
                if (workout.isFavorite) View.VISIBLE else View.GONE

            binding.root.setOnClickListener { onRouteClick(workout) }
        }

        private fun parseTrack(trackJson: String?): TrackData? {
            if (trackJson.isNullOrBlank()) return null
            return try {
                gson.fromJson(trackJson, TrackData::class.java)
            } catch (_: Exception) {
                null
            }
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<Workout>() {
        override fun areItemsTheSame(oldItem: Workout, newItem: Workout): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: Workout, newItem: Workout): Boolean =
            oldItem == newItem
    }
}
