package com.runner.academy.ui.workout

import android.app.DatePickerDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.runner.academy.R
import com.runner.academy.data.TrackData
import com.runner.academy.data.Workout
import com.runner.academy.data.WorkoutDatabase
import com.runner.academy.data.WorkoutType
import com.runner.academy.data.displayName
import com.runner.academy.databinding.DialogRoutePickerBinding
import com.runner.academy.databinding.FragmentAddWorkoutBinding
import com.runner.academy.util.TrackDataJson
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import androidx.recyclerview.widget.LinearLayoutManager

class AddWorkoutFragment : Fragment() {

    private var _binding: FragmentAddWorkoutBinding? = null
    private val binding get() = _binding!!

    private val viewModel: WorkoutViewModel by viewModels {
        val database = WorkoutDatabase.getDatabase(requireContext())
        val repository = com.runner.academy.data.WorkoutRepository(database.workoutDao())
        WorkoutViewModelFactory(repository)
    }

    private val workoutId: Long by lazy {
        arguments?.getLong(ARG_WORKOUT_ID, NO_WORKOUT_ID) ?: NO_WORKOUT_ID
    }

    private val isEditMode: Boolean
        get() = workoutId != NO_WORKOUT_ID

    private var selectedDate: Date = Date()
    private var selectedWorkoutType: WorkoutType = WorkoutType.EASY_RUN
    private var selectedTrackDataJson: String? = null
    private var editingIsFavorite: Boolean = false
    private var editingIntervalSegmentsJson: String? = null
    private var hasLoadedExistingWorkout: Boolean = false

    private val dateFormat = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAddWorkoutBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (isEditMode) {
            binding.textViewTitle.setText(R.string.edit_workout_title)
        }

        setupWorkoutTypeSpinner()
        setupClickListeners()
        setupDatePicker()
        updateRouteStatus()

        if (isEditMode) {
            loadExistingWorkout()
        }
    }

    private fun setupWorkoutTypeSpinner() {
        val workoutTypes = WorkoutType.entries
        val typeNames = workoutTypes.map { it.displayName(requireContext()) }

        val adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_dropdown_item_1line,
            typeNames
        )
        binding.autoCompleteTextViewType.setAdapter(adapter)

        binding.autoCompleteTextViewType.setOnItemClickListener { _, _, position, _ ->
            selectedWorkoutType = workoutTypes[position]
        }

        binding.autoCompleteTextViewType.setText(typeNames[0], false)
    }

    private fun setupDatePicker() {
        binding.editTextDate.setText(dateFormat.format(selectedDate))

        binding.editTextDate.setOnClickListener {
            val calendar = Calendar.getInstance().apply { time = selectedDate }
            DatePickerDialog(
                requireContext(),
                { _, year, month, dayOfMonth ->
                    calendar.set(year, month, dayOfMonth)
                    selectedDate = calendar.time
                    binding.editTextDate.setText(dateFormat.format(selectedDate))
                },
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH),
                calendar.get(Calendar.DAY_OF_MONTH)
            ).show()
        }
    }

    private fun setupClickListeners() {
        binding.buttonSave.setOnClickListener { saveWorkout() }
        binding.buttonCancel.setOnClickListener { findNavController().navigateUp() }
        binding.buttonSelectRoute.setOnClickListener { showRoutePicker() }
        binding.buttonClearRoute.setOnClickListener { clearRoute() }
    }

    private fun loadExistingWorkout() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val workout = viewModel.getWorkoutById(workoutId).first()
                if (!isAdded || isDetached) return@launch

                if (workout == null) {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.workout_invalid_id),
                        Toast.LENGTH_SHORT
                    ).show()
                    findNavController().navigateUp()
                    return@launch
                }

                bindWorkout(workout)
                hasLoadedExistingWorkout = true
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Error loading workout for edit: ${e.message}", e)
                if (isAdded) {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.route_load_error),
                        Toast.LENGTH_SHORT
                    ).show()
                    findNavController().navigateUp()
                }
            }
        }
    }

    private fun bindWorkout(workout: Workout) {
        selectedDate = workout.date
        selectedWorkoutType = workout.type
        selectedTrackDataJson = workout.trackData
        editingIsFavorite = workout.isFavorite
        editingIntervalSegmentsJson = workout.intervalSegmentsJson

        binding.editTextDate.setText(dateFormat.format(selectedDate))
        binding.autoCompleteTextViewType.setText(
            selectedWorkoutType.displayName(requireContext()),
            false
        )
        binding.editTextDistance.setText(formatDistance(workout.distance))

        val totalSeconds = (workout.duration / 1000).toInt()
        binding.editTextHours.setText((totalSeconds / 3600).toString())
        binding.editTextMinutes.setText(((totalSeconds % 3600) / 60).toString())
        binding.editTextSeconds.setText((totalSeconds % 60).toString())

        binding.editTextCalories.setText(workout.calories?.toString().orEmpty())
        binding.editTextNotes.setText(workout.notes.orEmpty())
        updateRouteStatus()
    }

    private fun showRoutePicker() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val workouts = viewModel.allWorkouts.first()
                    .filter { !it.trackData.isNullOrBlank() && it.id != workoutId }
                    .sortedWith(
                        compareByDescending<Workout> { it.isFavorite }
                            .thenByDescending { it.date }
                    )

                if (!isAdded || isDetached) return@launch

                if (workouts.isEmpty()) {
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.edit_workout_pick_route_title)
                        .setMessage(R.string.edit_workout_no_routes)
                        .setPositiveButton(R.string.cancel, null)
                        .show()
                    return@launch
                }

                val dialogBinding = DialogRoutePickerBinding.inflate(layoutInflater)
                val dialog = MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.edit_workout_pick_route_title)
                    .setView(dialogBinding.root)
                    .setNegativeButton(R.string.cancel, null)
                    .create()

                val adapter = RoutePickerAdapter { workout ->
                    dialog.dismiss()
                    onRouteSelected(workout)
                }
                dialogBinding.recyclerViewRoutes.layoutManager =
                    LinearLayoutManager(requireContext())
                dialogBinding.recyclerViewRoutes.adapter = adapter
                adapter.submitList(workouts)

                dialog.show()
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Error loading routes: ${e.message}", e)
                Toast.makeText(
                    requireContext(),
                    getString(R.string.route_load_error),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun onRouteSelected(source: Workout) {
        selectedTrackDataJson = source.trackData
        applyDistanceFromRoute(source)
        updateRouteStatus()
        Toast.makeText(requireContext(), getString(R.string.edit_workout_route_selected), Toast.LENGTH_SHORT)
            .show()
    }

    private fun applyDistanceFromRoute(source: Workout) {
        val trackJson = source.trackData
        val distanceKm = try {
            val trackData = trackJson?.let { TrackDataJson.parse(it) }
            if (trackData != null && trackData.totalDistance > 0) {
                trackData.totalDistance / 1000f
            } else {
                source.distance
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error reading route distance: ${e.message}", e)
            source.distance
        }
        binding.editTextDistance.setText(formatDistance(distanceKm))
    }

    private fun clearRoute() {
        selectedTrackDataJson = null
        updateRouteStatus()
        Toast.makeText(requireContext(), getString(R.string.edit_workout_route_cleared), Toast.LENGTH_SHORT)
            .show()
    }

    private fun updateRouteStatus() {
        val trackJson = selectedTrackDataJson
        if (trackJson.isNullOrBlank()) {
            binding.textViewRouteStatus.setText(R.string.edit_workout_route_none)
            binding.buttonClearRoute.isEnabled = false
            return
        }

        val pointCount = try {
            TrackDataJson.parse(trackJson)?.points?.size ?: 0
        } catch (_: Exception) {
            0
        }
        binding.textViewRouteStatus.text = getString(R.string.edit_workout_route_attached, pointCount)
        binding.buttonClearRoute.isEnabled = true
    }

    private fun formatDistance(distance: Float): String {
        return if (distance == distance.toLong().toFloat()) {
            distance.toLong().toString()
        } else {
            String.format(Locale.US, "%.2f", distance)
        }
    }

    private fun saveWorkout() {
        val distanceText = binding.editTextDistance.text.toString()
        val hoursText = binding.editTextHours.text.toString()
        val minutesText = binding.editTextMinutes.text.toString()
        val secondsText = binding.editTextSeconds.text.toString()
        val caloriesText = binding.editTextCalories.text.toString()
        val notesText = binding.editTextNotes.text.toString()

        if (distanceText.isBlank() || hoursText.isBlank() || minutesText.isBlank() || secondsText.isBlank()) {
            Toast.makeText(context, getString(R.string.fill_required_fields), Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val distance = distanceText.toFloat()
            val hours = hoursText.toInt()
            val minutes = minutesText.toInt()
            val seconds = secondsText.toInt()

            if (distance <= 0) {
                Toast.makeText(context, getString(R.string.distance_must_be_positive), Toast.LENGTH_SHORT).show()
                return
            }

            val totalSeconds = hours * 3600 + minutes * 60 + seconds
            val duration = totalSeconds * 1000L

            if (duration <= 0) {
                Toast.makeText(context, getString(R.string.time_must_be_positive), Toast.LENGTH_SHORT).show()
                return
            }

            val calories = if (caloriesText.isNotBlank()) {
                caloriesText.toIntOrNull()
            } else {
                null
            }

            val avgPace = viewModel.calculatePace(distance, duration)
            val workout = Workout(
                id = if (isEditMode) workoutId else 0,
                date = selectedDate,
                distance = distance,
                duration = duration,
                avgPace = avgPace,
                calories = calories,
                notes = notesText.ifBlank { null },
                type = selectedWorkoutType,
                trackData = selectedTrackDataJson,
                isFavorite = if (isEditMode) editingIsFavorite else false,
                intervalSegmentsJson = if (isEditMode) editingIntervalSegmentsJson else null
            )

            if (isEditMode) {
                if (!hasLoadedExistingWorkout) {
                    Toast.makeText(context, getString(R.string.workout_not_loaded), Toast.LENGTH_SHORT).show()
                    return
                }
                viewModel.updateWorkout(workout)
                Toast.makeText(context, getString(R.string.workout_updated), Toast.LENGTH_SHORT).show()
            } else {
                viewModel.insertWorkout(workout)
                Toast.makeText(context, getString(R.string.workout_saved), Toast.LENGTH_SHORT).show()
            }

            findNavController().navigateUp()
        } catch (e: NumberFormatException) {
            Toast.makeText(context, getString(R.string.check_input_data), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val TAG = "AddWorkoutFragment"
        const val ARG_WORKOUT_ID = "workoutId"
        const val NO_WORKOUT_ID = -1L
    }
}
