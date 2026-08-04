package com.runner.academy.ui.workout

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.runner.academy.R
import com.runner.academy.appContainer
import com.runner.academy.data.TrackData
import com.runner.academy.databinding.FragmentWorkoutDetailBinding
import com.runner.academy.util.ErrorHandler
import com.runner.academy.util.TrackDataJson
import com.runner.academy.util.WorkoutDataCleaner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WorkoutDetailFragment : Fragment() {

    private var _binding: FragmentWorkoutDetailBinding? = null
    private val binding get() = _binding!!

    private val args: WorkoutDetailFragmentArgs by navArgs()
    private val workoutId: Long
        get() = args.workoutId
    private val viewModel: WorkoutViewModel by viewModels {
        WorkoutViewModelFactory(requireContext().appContainer().workoutRepository)
    }

    private var currentWorkout: com.runner.academy.data.Workout? = null
    private var currentTrackData: TrackData? = null
    private var boundTrackDataJson: String? = null
    private var userPreferences: com.runner.academy.util.UserPreferences? = null

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

        userPreferences = requireContext().appContainer().userPreferences

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
        exportManager = ExportManager(requireContext(), viewLifecycleOwner)
        statsDisplay = DetailStatsDisplay(binding, requireContext(), viewModel)

        setupClickListeners()
        loadWorkout()
    }

    private fun setupClickListeners() {
        binding.buttonShare.setOnClickListener {
            currentWorkout?.let { workout ->
                exportManager?.shareWorkout(workout)
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

        binding.buttonEdit.setOnClickListener {
            findNavController().navigate(
                R.id.nav_add_workout,
                AddWorkoutFragmentArgs(workoutId = workoutId).toBundle()
            )
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
                    if (_binding == null || !isAdded || isDetached) return@collect

                    if (workout == null) {
                        android.util.Log.e("WorkoutDetail", "Workout not found with ID: $workoutId")
                        ErrorHandler.handleLoadError(requireContext(), Exception("Workout not found"))
                        return@collect
                    }

                    android.util.Log.d(
                        "WorkoutDetail",
                        "Workout loaded: ${workout.type}, distance: ${workout.distance}, has track data: ${workout.trackData != null}"
                    )

                    currentWorkout = workout
                    updateFavoriteButton(workout.isFavorite)
                    statsDisplay?.displayWorkout(workout)
                    chartRenderer?.intervalPlanSegments =
                        com.runner.academy.util.IntervalSegmentsJson.parse(workout.intervalSegmentsJson)

                    // Avoid clean→DB update→Flow→redraw loop (map flicker / missing track).
                    // Track is cleaned locally for display only when track JSON changes.
                    if (workout.trackData != boundTrackDataJson) {
                        boundTrackDataJson = workout.trackData
                        displayTrackOnMap(workout)
                    } else if (workout.trackData == null) {
                        _binding?.progressBarMapLoading?.visibility = View.GONE
                    } else {
                        currentTrackData?.let { chartRenderer?.updateSegmentsChartOnly(it) }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                android.util.Log.d("WorkoutDetail", "Loading cancelled: ${e.message}")
            } catch (e: Exception) {
                android.util.Log.e("WorkoutDetail", "Error loading workout: ${e.message}", e)
                if (_binding != null && isAdded && !isDetached) {
                    ErrorHandler.handleLoadError(requireContext(), e)
                }
            } finally {
                _binding?.progressBarMapLoading?.visibility = View.GONE
            }
        }
    }

    private fun displayTrackOnMap(workout: com.runner.academy.data.Workout) {
        val binding = _binding ?: return
        val trackDataJson = workout.trackData
        if (trackDataJson.isNullOrBlank()) {
            android.util.Log.w("WorkoutDetail", "No track data available for workout ${workout.id}")
            Toast.makeText(context, getString(R.string.route_not_saved), Toast.LENGTH_SHORT).show()
            return
        }

        binding.progressBarMapLoading.visibility = View.VISIBLE
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val cleanedTrackData = withContext(Dispatchers.Default) {
                    val trackData = TrackDataJson.parse(trackDataJson) ?: return@withContext null
                    if (trackData.points.isEmpty()) return@withContext trackData
                    if (WorkoutDataCleaner.needsCleaning(trackData)) {
                        WorkoutDataCleaner.cleanTrackData(trackData)
                    } else {
                        trackData
                    }
                }

                if (_binding == null || !isAdded || isDetached) return@launch
                _binding?.progressBarMapLoading?.visibility = View.GONE

                when {
                    cleanedTrackData == null -> {
                        Toast.makeText(context, getString(R.string.route_load_error), Toast.LENGTH_SHORT).show()
                    }
                    cleanedTrackData.points.isEmpty() -> {
                        Toast.makeText(context, getString(R.string.route_empty), Toast.LENGTH_SHORT).show()
                    }
                    else -> {
                        currentTrackData = cleanedTrackData
                        mapManager?.displayTrack(cleanedTrackData)
                        chartRenderer?.updateAllCharts(cleanedTrackData)
                    }
                }
            } catch (e: OutOfMemoryError) {
                android.util.Log.e("WorkoutDetail", "OOM loading track", e)
                _binding?.progressBarMapLoading?.visibility = View.GONE
                if (isAdded) {
                    Toast.makeText(context, getString(R.string.route_load_error), Toast.LENGTH_SHORT).show()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("WorkoutDetail", "Error parsing track data: ${e.message}", e)
                _binding?.progressBarMapLoading?.visibility = View.GONE
                if (isAdded) {
                    Toast.makeText(context, getString(R.string.route_load_error), Toast.LENGTH_SHORT).show()
                }
            }
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
