package com.runner.academy.ui.workout

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.runner.academy.R
import com.runner.academy.data.WorkoutDatabase
import com.runner.academy.databinding.FragmentWorkoutListBinding
import kotlinx.coroutines.launch

class WorkoutListFragment : Fragment() {

    private var _binding: FragmentWorkoutListBinding? = null
    private val binding get() = _binding!!

    private val viewModel: WorkoutViewModel by viewModels {
        val database = WorkoutDatabase.getDatabase(requireContext())
        val repository = com.runner.academy.data.WorkoutRepository(database.workoutDao())
        WorkoutViewModelFactory(repository)
    }

    private lateinit var workoutAdapter: WorkoutAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentWorkoutListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerView()
        setupClickListeners()
        setupFilterChips()
        setupSwipeRefresh()
        observeViewModel()
    }

    private fun setupSwipeRefresh() {
        binding.swipeRefreshWorkouts.setOnRefreshListener {
            viewModel.refreshStatistics()
            viewLifecycleOwner.lifecycleScope.launch {
                kotlinx.coroutines.delay(350)
                if (_binding != null) {
                    binding.swipeRefreshWorkouts.isRefreshing = false
                }
            }
        }
    }

    private fun setupRecyclerView() {
        workoutAdapter = WorkoutAdapter(
            context = requireContext(),
            onItemClick = { workout ->
                val bundle = Bundle().apply {
                    putLong("workoutId", workout.id)
                }
                findNavController().navigate(R.id.nav_workout_detail, bundle)
            },
            onFavoriteClick = { workout ->
                viewModel.toggleFavorite(workout)
            }
        )

        binding.recyclerViewWorkouts.apply {
            adapter = workoutAdapter
            layoutManager = LinearLayoutManager(context)
        }
    }

    private fun setupFilterChips() {
        binding.chipGroupWorkoutFilter.setOnCheckedStateChangeListener { _, checkedIds ->
            val filter = when {
                checkedIds.contains(R.id.chip_filter_favorites) -> WorkoutListFilter.FAVORITES
                else -> WorkoutListFilter.ALL
            }
            viewModel.setListFilter(filter)
        }
    }

    private fun setupClickListeners() {
        binding.fabAddWorkout.setOnClickListener {
            findNavController().navigate(R.id.nav_add_workout)
        }
        binding.buttonEmptyStartWorkout.setOnClickListener {
            findNavController().navigate(R.id.nav_tracking)
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                viewModel.displayedWorkouts.collect { workouts ->
                    if (isAdded && !isDetached) {
                        workoutAdapter.submitList(workouts)
                        updateEmptyState(workouts.isEmpty())
                        if (workouts.isNotEmpty() &&
                            viewModel.listFilter.value == WorkoutListFilter.ALL
                        ) {
                            cleanWorkoutsInBackground(workouts)
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                android.util.Log.d("WorkoutList", "Loading cancelled: ${e.message}")
            } catch (e: Exception) {
                android.util.Log.e("WorkoutList", "Error loading workouts: ${e.message}", e)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                viewModel.totalWorkouts.collect { total ->
                    if (isAdded && !isDetached) {
                        binding.textViewTotalWorkouts.text = total.toString()
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                android.util.Log.d("WorkoutList", "Total workouts loading cancelled")
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                viewModel.totalDistance.collect { distance ->
                    if (isAdded && !isDetached) {
                        binding.textViewTotalDistance.text = String.format("%.1f км", distance)
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                android.util.Log.d("WorkoutList", "Total distance loading cancelled")
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                viewModel.averagePace.collect { avgPace ->
                    if (isAdded && !isDetached) {
                        binding.textViewAvgPace.text = viewModel.formatPace(avgPace)
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                android.util.Log.d("WorkoutList", "Average pace loading cancelled")
            }
        }
    }

    private fun updateEmptyState(isEmpty: Boolean) {
        if (isEmpty) {
            binding.layoutEmptyState.visibility = View.VISIBLE
            binding.recyclerViewWorkouts.visibility = View.GONE
            val favoritesOnly = viewModel.listFilter.value == WorkoutListFilter.FAVORITES
            if (favoritesOnly) {
                binding.textViewEmptyTitle.setText(R.string.workout_list_message_no_favorites)
                binding.textViewEmptySubtitle.setText(R.string.workout_list_message_add_favorites)
                binding.buttonEmptyStartWorkout.visibility = View.GONE
            } else {
                binding.textViewEmptyTitle.setText(R.string.workout_list_message_no_workouts)
                binding.textViewEmptySubtitle.setText(R.string.workout_list_message_start_workout)
                binding.buttonEmptyStartWorkout.visibility = View.VISIBLE
            }
        } else {
            binding.layoutEmptyState.visibility = View.GONE
            binding.recyclerViewWorkouts.visibility = View.VISIBLE
        }
    }

    private fun cleanWorkoutsInBackground(workouts: List<com.runner.academy.data.Workout>) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                var cleanedCount = 0
                for (workout in workouts) {
                    if (workout.trackData != null) {
                        val cleanedWorkout = viewModel.cleanWorkoutData(workout)
                        if (cleanedWorkout != null && cleanedWorkout != workout) {
                            cleanedCount++
                        }
                    }
                }
                if (cleanedCount > 0) {
                    android.util.Log.d("WorkoutList", "Cleaned $cleanedCount workouts in background")
                }
            } catch (e: Exception) {
                android.util.Log.e(
                    "WorkoutList",
                    "Error cleaning workouts in background: ${e.message}",
                    e
                )
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
