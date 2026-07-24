package com.drrhaos.runner.ui.workout

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.drrhaos.runner.R
import com.drrhaos.runner.data.TrackData
import com.drrhaos.runner.data.WorkoutDatabase
import com.drrhaos.runner.databinding.FragmentWorkoutDetailBinding
import com.drrhaos.runner.util.ErrorHandler
import com.drrhaos.runner.util.WorkoutDataCleaner
import com.google.gson.Gson
import kotlinx.coroutines.launch

class WorkoutDetailFragment : Fragment() {

    private var _binding: FragmentWorkoutDetailBinding? = null
    private val binding get() = _binding!!

    private val workoutId: Long by lazy {
        arguments?.getLong("workoutId") ?: -1L
    }
    private val viewModel: WorkoutViewModel by viewModels {
        val database = WorkoutDatabase.getDatabase(requireContext())
        val repository = com.drrhaos.runner.data.WorkoutRepository(database.workoutDao())
        WorkoutViewModelFactory(repository)
    }

    private var currentWorkout: com.drrhaos.runner.data.Workout? = null
    private var currentTrackData: TrackData? = null
    private var userPreferences: com.drrhaos.runner.util.UserPreferences? = null

    // Extracted manager components
    private var mapManager: DetailMapManager? = null
    private var chartRenderer: ChartRenderer? = null
    private var exportManager: ExportManager? = null
    private var statsDisplay: DetailStatsDisplay? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentWorkoutDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        userPreferences = com.drrhaos.runner.util.UserPreferences(requireContext())

        // Initialize extracted managers
        mapManager = DetailMapManager(binding.mapViewDetail, requireContext()).apply { initialize() }
        chartRenderer = ChartRenderer(
            paceSpeedHeartChart = binding.chartPaceSpeedHeart,
            elevationChart = binding.chartElevation,
            segmentsChart = binding.chartSegments,
            textViewPaceSpeedValues = binding.textViewChartPaceSpeedHeartValues,
            textViewElevationValues = binding.textViewChartElevationValues,
            context = requireContext(),
            userPreferences = userPreferences,
            onPositionSelected = { point -> mapManager?.showPositionOnMap(point) },
            onSegmentSelected = { start, end -> mapManager?.showSegmentOnMap(start, end) },
            onNothingSelected = { mapManager?.hidePositionMarkers() }
        )
        exportManager = ExportManager(requireActivity())
        statsDisplay = DetailStatsDisplay(binding, requireContext(), viewModel)

        setupClickListeners()
        loadWorkout()
    }

    private fun setupClickListeners() {
        binding.buttonShare.setOnClickListener {
            currentWorkout?.let { workout ->
                exportManager?.shareWorkout(
                    workout,
                    formatDuration = viewModel::formatDuration,
                    formatPace = viewModel::formatPace
                )
            }
        }

        binding.buttonExportGpx.setOnClickListener {
            currentWorkout?.let { workout ->
                exportManager?.exportWorkoutToGpx(workout)
            } ?: run {
                Toast.makeText(requireContext(), getString(R.string.workout_not_loaded), Toast.LENGTH_SHORT).show()
            }
        }

        binding.buttonDelete.setOnClickListener {
            showDeleteConfirmationDialog()
        }

        binding.buttonFavorite.setOnClickListener {
            currentWorkout?.let { workout ->
                val willBeFavorite = !workout.isFavorite
                viewModel.toggleFavorite(workout)
                currentWorkout = workout.copy(isFavorite = willBeFavorite)
                updateFavoriteButton(willBeFavorite)
                val message = if (willBeFavorite) {
                    R.string.workout_favorite_added
                } else {
                    R.string.workout_favorite_removed
                }
                Toast.makeText(requireContext(), getString(message), Toast.LENGTH_SHORT).show()
            }
        }

        binding.buttonBasicInfo.isChecked = true

        binding.toggleGroupAnalysis.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                when (checkedId) {
                    binding.buttonBasicInfo.id -> {
                        binding.layoutBasicInfo.visibility = View.VISIBLE
                        binding.layoutCharts.visibility = View.GONE
                    }
                    binding.buttonCharts.id -> {
                        binding.layoutBasicInfo.visibility = View.GONE
                        binding.layoutCharts.visibility = View.VISIBLE
                    }
                }
            }
        }

        binding.chipGroupSegmentDisplay.setOnCheckedStateChangeListener { group, checkedIds ->
            when {
                binding.chipPace.isChecked -> {
                    chartRenderer?.segmentsDisplayMode = ChartRenderer.SegmentsDisplayMode.PACE
                    currentTrackData?.let { chartRenderer?.updateSegmentsChartOnly(it) }
                }
                binding.chipSpeed.isChecked -> {
                    chartRenderer?.segmentsDisplayMode = ChartRenderer.SegmentsDisplayMode.SPEED
                    currentTrackData?.let { chartRenderer?.updateSegmentsChartOnly(it) }
                }
            }
        }
    }

    private fun loadWorkout() {
        android.util.Log.d("WorkoutDetail", "Loading workout with ID: $workoutId")

        if (workoutId == -1L) {
            android.util.Log.e("WorkoutDetail", "Invalid workout ID: $workoutId")
            Toast.makeText(requireContext(), getString(R.string.workout_invalid_id), Toast.LENGTH_SHORT).show()
            findNavController().navigateUp()
            return
        }

        binding.progressBarMapLoading.visibility = View.VISIBLE

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                viewModel.getWorkoutById(workoutId).collect { workout ->
                    if (isAdded && !isDetached) {
                        workout?.let {
                            android.util.Log.d("WorkoutDetail", "Workout loaded: ${it.type}, distance: ${it.distance}, has track data: ${it.trackData != null}")

                            viewModel.cleanWorkoutData(it)?.let { cleanedWorkout ->
                                android.util.Log.d("WorkoutDetail", "Using cleaned workout data")
                                currentWorkout = cleanedWorkout
                                statsDisplay?.displayWorkout(cleanedWorkout)
                                updateFavoriteButton(cleanedWorkout.isFavorite)
                                displayTrackOnMap(cleanedWorkout)
                            } ?: run {
                                android.util.Log.d("WorkoutDetail", "Using original workout data")
                                currentWorkout = it
                                statsDisplay?.displayWorkout(it)
                                updateFavoriteButton(it.isFavorite)
                                displayTrackOnMap(it)
                            }
                        } ?: run {
                            android.util.Log.e("WorkoutDetail", "Workout not found with ID: $workoutId")
                            ErrorHandler.handleLoadError(requireContext(), Exception("Workout not found"))
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                android.util.Log.d("WorkoutDetail", "Loading cancelled: ${e.message}")
            } catch (e: Exception) {
                android.util.Log.e("WorkoutDetail", "Error loading workout: ${e.message}", e)
                if (isAdded && !isDetached) {
                    ErrorHandler.handleLoadError(requireContext(), e)
                }
            } finally {
                if (isAdded && !isDetached) {
                    binding.progressBarMapLoading.visibility = View.GONE
                }
            }
        }
    }

    private fun displayTrackOnMap(workout: com.drrhaos.runner.data.Workout) {
        binding.progressBarMapLoading.visibility = View.GONE

        workout.trackData?.let { trackDataJson ->
            try {
                val gson = Gson()
                val trackData = gson.fromJson(trackDataJson, TrackData::class.java)

                if (trackData.points.isNotEmpty()) {
                    android.util.Log.d("WorkoutDetail", "Original track data has ${trackData.points.size} points")

                    val needsCleaning = WorkoutDataCleaner.needsCleaning(trackData)
                    android.util.Log.d("WorkoutDetail", "Track data needs cleaning: $needsCleaning")

                    val cleanedTrackData = if (needsCleaning) {
                        android.util.Log.d("WorkoutDetail", "Cleaning track data...")
                        WorkoutDataCleaner.cleanTrackData(trackData)
                    } else {
                        trackData
                    }

                    android.util.Log.d("WorkoutDetail", "Using ${cleanedTrackData.points.size} points for display")

                    currentTrackData = cleanedTrackData
                    mapManager?.displayTrack(cleanedTrackData)
                    chartRenderer?.updateAllCharts(cleanedTrackData)

                    android.util.Log.d("WorkoutDetail", "Successfully loaded track with ${cleanedTrackData.points.size} points")
                } else {
                    android.util.Log.w("WorkoutDetail", "Track data is empty")
                    Toast.makeText(context, getString(R.string.route_empty), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                android.util.Log.e("WorkoutDetail", "Error parsing track data: ${e.message}", e)
                Toast.makeText(context, getString(R.string.route_load_error), Toast.LENGTH_SHORT).show()
            }
        } ?: run {
            android.util.Log.w("WorkoutDetail", "No track data available for workout ${workout.id}")
            Toast.makeText(context, getString(R.string.route_not_saved), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        mapManager?.onResume()
    }

    override fun onPause() {
        super.onPause()
        mapManager?.onPause()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        mapManager?.onDetach()
        chartRenderer = null
        exportManager = null
        statsDisplay = null
        mapManager = null
        _binding = null
    }

    private fun updateFavoriteButton(isFavorite: Boolean) {
        if (isFavorite) {
            binding.buttonFavorite.setImageResource(R.drawable.ic_star)
            binding.buttonFavorite.contentDescription = getString(R.string.workout_favorite_remove)
        } else {
            binding.buttonFavorite.setImageResource(R.drawable.ic_star_border)
            binding.buttonFavorite.contentDescription = getString(R.string.workout_favorite_add)
        }
    }

    private fun showDeleteConfirmationDialog() {
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.delete_workout_title))
            .setMessage(getString(R.string.delete_workout_message))
            .setPositiveButton(getString(R.string.delete)) { _, _ ->
                deleteWorkout()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .setIcon(android.R.drawable.ic_dialog_alert)
            .show()
    }

    private fun deleteWorkout() {
        currentWorkout?.let { workout ->
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    viewModel.deleteWorkout(workout)
                    Toast.makeText(context, getString(R.string.workout_deleted), Toast.LENGTH_SHORT).show()
                    findNavController().navigateUp()
                } catch (e: Exception) {
                    android.util.Log.e("WorkoutDetail", "Error deleting workout: ${e.message}", e)
                    ErrorHandler.handleSaveError(requireContext(), e)
                }
            }
        }
    }
}
