package com.example.runner.ui.workout

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.runner.data.WorkoutDatabase
import com.example.runner.databinding.FragmentWorkoutListBinding
import kotlinx.coroutines.launch

class WorkoutListFragment : Fragment() {

    private var _binding: FragmentWorkoutListBinding? = null
    private val binding get() = _binding!!

    private val viewModel: WorkoutViewModel by viewModels {
        val database = WorkoutDatabase.getDatabase(requireContext())
        WorkoutViewModelFactory(database.workoutDao())
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
        workoutAdapter = WorkoutAdapter { workout ->
            // Переход к детальному просмотру тренировки
            val bundle = Bundle().apply {
                putLong("workoutId", workout.id)
            }
            findNavController().navigate(com.example.runner.R.id.nav_workout_detail, bundle)
        }

        binding.recyclerViewWorkouts.apply {
            adapter = workoutAdapter
            layoutManager = LinearLayoutManager(context)
        }
    }

    private fun setupClickListeners() {
        // FAB кнопка добавления тренировки
        binding.fabAddWorkout.setOnClickListener {
            findNavController().navigate(com.example.runner.R.id.nav_add_workout)
        }
        binding.buttonEmptyStartWorkout.setOnClickListener {
            findNavController().navigate(com.example.runner.R.id.nav_tracking)
        }
    }

    private fun observeViewModel() {
        // Наблюдаем за списком тренировок
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                viewModel.allWorkouts.collect { workouts ->
                    if (isAdded && !isDetached) { // Проверяем, что Fragment еще активен
                        workoutAdapter.submitList(workouts)
                        
                        // Показываем/скрываем пустое состояние
                        if (workouts.isEmpty()) {
                            binding.layoutEmptyState.visibility = View.VISIBLE
                            binding.recyclerViewWorkouts.visibility = View.GONE
                        } else {
                            binding.layoutEmptyState.visibility = View.GONE
                            binding.recyclerViewWorkouts.visibility = View.VISIBLE
                            
                            // Фоновая очистка данных тренировок
                            cleanWorkoutsInBackground(workouts)
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                android.util.Log.d("WorkoutList", "Loading cancelled: ${e.message}")
                // Не показываем ошибку для отмененных корутин
            } catch (e: Exception) {
                android.util.Log.e("WorkoutList", "Error loading workouts: ${e.message}", e)
            }
        }

        // Наблюдаем за статистикой
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
                viewModel.averageDuration.collect { duration ->
                    if (isAdded && !isDetached) {
                        val avgPace = if (duration > 0 && viewModel.totalDistance.value > 0) {
                            val durationMinutes = duration / 60000f
                            val avgDistance = viewModel.totalDistance.value
                            durationMinutes / avgDistance
                        } else {
                            0f
                        }
                        binding.textViewAvgPace.text = viewModel.formatPace(avgPace)
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                android.util.Log.d("WorkoutList", "Average duration loading cancelled")
            }
        }
    }
    
    /**
     * Фоновая очистка данных тренировок
     */
    private fun cleanWorkoutsInBackground(workouts: List<com.example.runner.data.Workout>) {
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
                android.util.Log.e("WorkoutList", "Error cleaning workouts in background: ${e.message}", e)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
