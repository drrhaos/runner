package com.runner.academy.ui.workout

import android.app.DatePickerDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.view.MenuProvider
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.runner.academy.R
import com.runner.academy.appContainer
import com.runner.academy.data.Workout
import com.runner.academy.data.WorkoutType
import com.runner.academy.data.displayName
import com.runner.academy.databinding.DialogRoutePickerBinding
import com.runner.academy.databinding.FragmentAddWorkoutBinding
import com.runner.academy.util.ErrorHandler
import com.runner.academy.util.FormatUtils
import com.runner.academy.util.TrackDataJson
import com.runner.academy.util.WorkoutTrackRebuilder
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import androidx.recyclerview.widget.LinearLayoutManager

/**
 * Состояние формы, переживающее пересоздание view (поворот экрана).
 * Тексты полей восстанавливает сам Android; здесь — то, что в полях не хранится.
 */
class AddWorkoutFormState : ViewModel() {
    var initialized = false
    /** Тренировка до редактирования: её трек — источник времени при замене маршрута. */
    var originalWorkout: Workout? = null
    var selectedDate: Date = Date()
    var selectedType: WorkoutType = WorkoutType.EASY_RUN
    var selectedTrackDataJson: String? = null
    /** Калории считаются от дистанции, пока пользователь не ввёл их вручную. */
    var caloriesAuto = true
    var initialSnapshot: AddWorkoutFragment.FormSnapshot? = null
}

class AddWorkoutFragment : Fragment() {

    private var _binding: FragmentAddWorkoutBinding? = null
    private val binding get() = _binding!!

    private val viewModel: WorkoutViewModel by viewModels {
        WorkoutViewModelFactory(requireContext().appContainer().workoutRepository)
    }
    private val form: AddWorkoutFormState by viewModels()

    private val workoutId: Long by lazy {
        arguments?.getLong("workoutId", NO_WORKOUT_ID) ?: NO_WORKOUT_ID
    }

    private val isEditMode: Boolean
        get() = workoutId != NO_WORKOUT_ID

    private var isSaving: Boolean = false
    /** Поля заполняются кодом — не считать это ручным вводом калорий. */
    private var isBindingFields: Boolean = false

    private val dateFormat = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())

    data class FormSnapshot(
        val date: Date,
        val type: WorkoutType,
        val trackDataJson: String?,
        val distance: String,
        val hours: String,
        val minutes: String,
        val seconds: String,
        val calories: String,
        val notes: String
    )

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
        setupBackHandling()
        updateRouteStatus()
        updateFieldLocks()

        if (!form.initialized) {
            if (isEditMode) {
                loadExistingWorkout()
            } else {
                form.initialized = true
                form.initialSnapshot = snapshot()
            }
        }
    }

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)
        // После восстановления текстов, чтобы восстановление не считалось ручным вводом.
        setupCaloriesAutoCalculation()
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
            form.selectedType = workoutTypes[position]
        }

        binding.autoCompleteTextViewType.setText(form.selectedType.displayName(requireContext()), false)
    }

    private fun setupDatePicker() {
        binding.editTextDate.setText(dateFormat.format(form.selectedDate))

        binding.editTextDate.setOnClickListener {
            val calendar = Calendar.getInstance().apply { time = form.selectedDate }
            DatePickerDialog(
                requireContext(),
                { _, year, month, dayOfMonth ->
                    calendar.set(year, month, dayOfMonth)
                    form.selectedDate = calendar.time
                    binding.editTextDate.setText(dateFormat.format(form.selectedDate))
                },
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH),
                calendar.get(Calendar.DAY_OF_MONTH)
            ).show()
        }
    }

    private fun setupClickListeners() {
        binding.buttonSave.setOnClickListener { saveWorkout() }
        binding.buttonCancel.setOnClickListener { leaveScreen() }
        binding.buttonSelectRoute.setOnClickListener { showRoutePicker() }
        binding.buttonClearRoute.setOnClickListener { confirmClearRoute() }
    }

    private fun setupBackHandling() {
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = leaveScreen()
            }
        )
        // Toolbar "Up" bypasses the back dispatcher (MainActivity.onSupportNavigateUp).
        requireActivity().addMenuProvider(
            object : MenuProvider {
                override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) = Unit

                override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                    if (menuItem.itemId != android.R.id.home) return false
                    leaveScreen()
                    return true
                }
            },
            viewLifecycleOwner
        )
    }

    private fun setupCaloriesAutoCalculation() {
        binding.editTextDistance.doAfterTextChanged { text ->
            if (!form.caloriesAuto) return@doAfterTextChanged
            val distance = text?.toString()?.toFloatOrNull() ?: return@doAfterTextChanged
            setCaloriesText(calculateCalories(distance).toString())
        }
        binding.editTextCalories.doAfterTextChanged { text ->
            if (isBindingFields) return@doAfterTextChanged
            // Ручной ввод отключает автоподсчёт; пустое поле возвращает его.
            form.caloriesAuto = text.isNullOrBlank()
        }
    }

    private fun setCaloriesText(value: String) {
        isBindingFields = true
        try {
            binding.editTextCalories.setText(value)
        } finally {
            isBindingFields = false
        }
    }

    private fun calculateCalories(distanceKm: Float): Int =
        FormatUtils.calculateCalories(distanceKm, requireContext().appContainer().userPreferences.userWeight)

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

                form.originalWorkout = workout
                bindWorkout(workout)
                form.initialized = true
                form.initialSnapshot = snapshot()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
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
        form.selectedDate = workout.date
        form.selectedType = workout.type
        form.selectedTrackDataJson = workout.trackData
        // Калории, совпадающие с авторасчётом, продолжают пересчитываться от дистанции.
        form.caloriesAuto = workout.calories == null ||
            workout.calories == calculateCalories(workout.distance)

        isBindingFields = true
        try {
            binding.editTextDate.setText(dateFormat.format(form.selectedDate))
            binding.autoCompleteTextViewType.setText(
                form.selectedType.displayName(requireContext()),
                false
            )
            binding.editTextCalories.setText(workout.calories?.toString().orEmpty())
            binding.editTextDistance.setText(formatDistance(workout.distance))

            val totalSeconds = (workout.duration / 1000).toInt()
            binding.editTextHours.setText((totalSeconds / 3600).toString())
            binding.editTextMinutes.setText(((totalSeconds % 3600) / 60).toString())
            binding.editTextSeconds.setText((totalSeconds % 60).toString())

            binding.editTextNotes.setText(workout.notes.orEmpty())
        } finally {
            isBindingFields = false
        }
        updateRouteStatus()
        updateFieldLocks()
    }

    /**
     * Свой записанный трек задаёт дистанцию и время — их правка разошлась бы с графиками.
     * Тип тренировки с интервальным планом менять нельзя.
     */
    private fun updateFieldLocks() {
        val original = form.originalWorkout
        val trackLocked = original != null &&
            !original.trackData.isNullOrBlank() &&
            form.selectedTrackDataJson == original.trackData
        binding.textInputLayoutDistance.isEnabled = !trackLocked
        binding.textInputLayoutDistance.helperText =
            if (trackLocked) getString(R.string.edit_workout_track_locked_hint) else null
        binding.editTextHours.isEnabled = !trackLocked
        binding.editTextMinutes.isEnabled = !trackLocked
        binding.editTextSeconds.isEnabled = !trackLocked

        val typeLocked = !original?.intervalSegmentsJson.isNullOrBlank()
        binding.textInputLayoutType.isEnabled = !typeLocked
        binding.textInputLayoutType.helperText =
            if (typeLocked) getString(R.string.edit_workout_type_locked_hint) else null
    }

    private fun showRoutePicker() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // Counted first; routes themselves are paged (hundreds of tracks must not load at once)
                val routeCount = viewModel.countRoutes(excludeId = workoutId)

                if (!isAdded || isDetached) return@launch

                if (routeCount == 0) {
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
                val pagingJob = viewLifecycleOwner.lifecycleScope.launch {
                    viewModel.routePages(excludeId = workoutId).collectLatest(adapter::submitData)
                }
                dialog.setOnDismissListener { pagingJob.cancel() }

                dialog.show()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
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
        form.selectedTrackDataJson = source.trackData
        updateFieldLocks()
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

    private fun confirmClearRoute() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.edit_workout_clear_route_title)
            .setMessage(R.string.edit_workout_clear_route_message)
            .setPositiveButton(R.string.edit_workout_clear_route) { _, _ -> clearRoute() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun clearRoute() {
        form.selectedTrackDataJson = null
        updateRouteStatus()
        updateFieldLocks()
        Toast.makeText(requireContext(), getString(R.string.edit_workout_route_cleared), Toast.LENGTH_SHORT)
            .show()
    }

    private fun updateRouteStatus() {
        val trackJson = form.selectedTrackDataJson
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

    private fun snapshot() = FormSnapshot(
        date = form.selectedDate,
        type = form.selectedType,
        trackDataJson = form.selectedTrackDataJson,
        distance = binding.editTextDistance.text.toString(),
        hours = binding.editTextHours.text.toString(),
        minutes = binding.editTextMinutes.text.toString(),
        seconds = binding.editTextSeconds.text.toString(),
        calories = binding.editTextCalories.text.toString(),
        notes = binding.editTextNotes.text.toString()
    )

    private fun hasUnsavedChanges(): Boolean {
        val initial = form.initialSnapshot ?: return false
        return snapshot() != initial
    }

    private fun leaveScreen() {
        if (isSaving || !hasUnsavedChanges()) {
            findNavController().navigateUp()
            return
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.edit_workout_discard_title)
            .setMessage(R.string.edit_workout_discard_message)
            .setPositiveButton(R.string.edit_workout_discard_confirm) { _, _ ->
                findNavController().navigateUp()
            }
            .setNegativeButton(R.string.edit_workout_keep_editing, null)
            .show()
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

            if (minutes !in 0..59 || seconds !in 0..59) {
                Toast.makeText(context, getString(R.string.edit_workout_time_range_error), Toast.LENGTH_SHORT).show()
                return
            }

            val totalSeconds = hours * 3600 + minutes * 60 + seconds
            val duration = totalSeconds * 1000L

            if (duration <= 0) {
                Toast.makeText(context, getString(R.string.time_must_be_positive), Toast.LENGTH_SHORT).show()
                return
            }

            val calories = if (caloriesText.isNotBlank()) {
                caloriesText.toIntOrNull()?.takeIf { it >= 0 } ?: run {
                    Toast.makeText(context, getString(R.string.edit_workout_calories_invalid), Toast.LENGTH_SHORT).show()
                    return
                }
            } else {
                null
            }

            val original = form.originalWorkout
            if (isEditMode && original == null) {
                Toast.makeText(context, getString(R.string.workout_not_loaded), Toast.LENGTH_SHORT).show()
                return
            }

            val avgPace = viewModel.calculatePace(distance, duration)
            val workout = Workout(
                id = original?.id ?: 0,
                date = form.selectedDate,
                distance = distance,
                duration = duration,
                avgPace = avgPace,
                calories = calories,
                notes = notesText.ifBlank { null },
                type = form.selectedType,
                trackData = form.selectedTrackDataJson,
                isFavorite = original?.isFavorite ?: false,
                intervalSegmentsJson = original?.intervalSegmentsJson
            )
            persistWorkout(workout, original)
        } catch (e: NumberFormatException) {
            Toast.makeText(context, getString(R.string.check_input_data), Toast.LENGTH_SHORT).show()
        }
    }

    private fun persistWorkout(workout: Workout, original: Workout?) {
        if (isSaving) return
        isSaving = true
        binding.buttonSave.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val timeSource = viewModel.saveFromForm(workout, original)
                val ctx = context ?: return@launch
                val message = when (timeSource) {
                    WorkoutTrackRebuilder.TimeSource.RECORDED -> R.string.edit_workout_route_time_recorded
                    WorkoutTrackRebuilder.TimeSource.UNIFORM -> R.string.edit_workout_route_time_uniform
                    else -> if (original != null) R.string.workout_updated else R.string.workout_saved
                }
                Toast.makeText(ctx, getString(message), Toast.LENGTH_SHORT).show()
                findNavController().navigateUp()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Error saving workout: ${e.message}", e)
                isSaving = false
                _binding?.buttonSave?.isEnabled = true
                context?.let { ErrorHandler.handleSaveError(it, e) }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val TAG = "AddWorkoutFragment"
        const val NO_WORKOUT_ID = -1L
    }
}
