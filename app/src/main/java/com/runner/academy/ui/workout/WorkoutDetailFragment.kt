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
import com.runner.academy.R
import com.runner.academy.appContainer
import com.runner.academy.data.TrackData
import com.runner.academy.data.knownAltitude
import com.runner.academy.databinding.FragmentWorkoutDetailBinding
import com.runner.academy.util.DisplayTrack
import com.runner.academy.util.ErrorHandler
import com.runner.academy.util.ShareExports
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WorkoutDetailFragment : Fragment() {

    private var _binding: FragmentWorkoutDetailBinding? = null
    private val binding get() = _binding!!

    /** Bundle key from Safe Args / navigation graph — do not use reflective navArgs. */
    private val workoutId: Long by lazy {
        arguments?.getLong("workoutId", -1L) ?: -1L
    }
    private val viewModel: WorkoutViewModel by viewModels {
        WorkoutViewModelFactory(requireContext().appContainer().workoutRepository)
    }

    private var currentWorkout: com.runner.academy.data.Workout? = null
    private var currentTrackData: TrackData? = null
    /** The shown track has steps: a missing cadence is then "not computed yet", not "none". */
    private var currentTrackHasSteps = false
    /** The shown track has altitudes: missing gain and loss are then "not computed yet". */
    private var currentTrackHasAltitude = false
    private var boundTrackRenderKey: Pair<com.runner.academy.data.WorkoutType, String>? = null
    private var userPreferences: com.runner.academy.util.UserPreferences? = null
    private var isDeleting = false

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
            elevation = ElevationChartViews(
                card = binding.cardChartElevation,
                chart = binding.chartElevation,
                values = binding.textViewChartElevationValues,
                summary = binding.textViewElevationChartSummary,
                source = binding.textViewElevationChartSource
            ),
            segmentsChart = binding.chartSegments,
            textViewPaceSpeedValues = binding.textViewChartPaceSpeedHeartValues,
            cadence = CadenceChartViews(
                card = binding.cardChartCadence,
                chart = binding.chartCadence,
                values = binding.textViewChartCadenceValues,
                average = binding.textViewChartCadenceAvg
            ),
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

        binding.buttonShareDiagnostics.setOnClickListener {
            showShareDiagnosticsDialog()
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
                viewModel.toggleFavorite(workout.id, workout.isFavorite)
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
                        if (isDeleting) return@collect
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
                    val hasDiagnostics = viewModel.hasGpsDiagnostics(workoutId)
                    _binding?.buttonShareDiagnostics?.visibility =
                        if (hasDiagnostics) View.VISIBLE else View.GONE
                    statsDisplay?.displayWorkout(workout)
                    showCadence(workout)
                    showElevation(workout)
                    chartRenderer?.intervalPlanSegments =
                        com.runner.academy.util.IntervalSegmentsJson.parse(workout.intervalSegmentsJson)

                    // Row updates (favorite, background metrics) must not redraw the map (flicker).
                    // The display track is rebuilt and redrawn only when its inputs change:
                    // the JSON itself or the type (type drives the GPS outlier threshold).
                    val renderKey = workout.trackData?.let { workout.type to it }
                    if (renderKey != boundTrackRenderKey) {
                        boundTrackRenderKey = renderKey
                        if (workout.trackData == null) {
                            currentTrackData = null
                            currentTrackHasSteps = false
                            currentTrackHasAltitude = false
                            showCadence(workout)
                            showElevation(workout)
                            _binding?.progressBarMapLoading?.visibility = View.GONE
                        } else {
                            displayTrackOnMap(workout)
                        }
                    } else {
                        _binding?.progressBarMapLoading?.visibility = View.GONE
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
                    DisplayTrack.of(trackDataJson, workout.type)
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
                        withContext(Dispatchers.Default) {
                            currentTrackHasSteps = cleanedTrackData.points.any { it.steps != null }
                            currentTrackHasAltitude = cleanedTrackData.points.any { it.knownAltitude() != null }
                        }
                        if (_binding == null || !isAdded || isDetached) return@launch
                        mapManager?.displayTrack(cleanedTrackData)
                        chartRenderer?.updateAllCharts(cleanedTrackData)
                        currentWorkout?.let {
                            showCadence(it)
                            showElevation(it)
                        }
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

    /** The tile and the chart title from the same stored cadence (see [CadenceDisplay]). */
    private fun showCadence(workout: com.runner.academy.data.Workout) {
        val display = CadenceDisplay.of(workout.avgCadence, workout.metricsVersion, currentTrackHasSteps)
        statsDisplay?.displayCadence(display)
        chartRenderer?.cadenceDisplay = display
    }

    /** The tiles and the chart title from the same stored gain and loss (see [ElevationDisplay]). */
    private fun showElevation(workout: com.runner.academy.data.Workout) {
        val display = ElevationDisplay.of(
            workout.elevationGain,
            workout.elevationLoss,
            workout.elevationSource,
            workout.metricsVersion,
            currentTrackHasAltitude
        )
        statsDisplay?.displayElevation(display) { source -> showElevationSourceDialog(source) }
        chartRenderer?.elevationDisplay = display
    }

    /** Why GPS (or a file's) elevation is approximate. */
    private fun showElevationSourceDialog(source: com.runner.academy.data.ElevationSource) {
        val (title, message) = ElevationText.sourceDialog(source) ?: return
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
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
        // Fragment stays in the back stack (e.g. while editing): the new view must redraw from scratch.
        boundTrackRenderKey = null
        currentTrackData = null
        currentTrackHasSteps = false
        currentTrackHasAltitude = false
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

    /** The file holds exact coordinates (usually including home), so warn before it leaves the phone. */
    private fun showShareDiagnosticsDialog() {
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.share_diagnostics_title))
            .setMessage(getString(R.string.share_diagnostics_message))
            .setPositiveButton(getString(R.string.share_diagnostics_confirm)) { _, _ ->
                shareDiagnostics()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun shareDiagnostics() {
        val context = requireContext()
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val copy = viewModel.copyGpsDiagnosticsForSharing(workoutId) ?: return@launch
                ShareExports.shareFile(
                    context,
                    copy,
                    "text/plain",
                    getString(R.string.share_diagnostics_chooser)
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("WorkoutDetail", "Share diagnostics failed", e)
                Toast.makeText(context, getString(R.string.share_diagnostics_failed), Toast.LENGTH_SHORT).show()
            }
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
            if (isDeleting) return
            isDeleting = true
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    viewModel.deleteWorkout(workout)
                    requireContext().appContainer().trainingPlanRepository
                        .unlinkCompletedWorkout(workout.id)
                    Toast.makeText(context, getString(R.string.workout_deleted), Toast.LENGTH_SHORT).show()
                    findNavController().navigateUp()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.e("WorkoutDetail", "Error deleting workout: ${e.message}", e)
                    isDeleting = false
                    ErrorHandler.handleSaveError(requireContext(), e)
                }
            }
        }
    }
}
